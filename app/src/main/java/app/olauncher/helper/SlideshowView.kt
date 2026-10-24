package app.olauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.SlideshowOrder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The launcher's own photo slideshow widget. It shares the touch handling of app widgets
 * (editing, dragging, the tap settings) and shows its photos in random order with a crossfade,
 * never repeating one before all others were shown, even across restarts,
 * moving on every [intervalSeconds] while resumed. A tap (or double tap, per its tap setting) opens
 * the photo showing, through [onOpenPhoto]. Each time the home screen is hidden
 * it switches photo unseen, so a new one is already showing on return. It remembers what it showed,
 * so when the home screen is rebuilt (after the app drawer or settings) it picks up where it was.
 */
class SlideshowView(
    context: Context,
    private val slideshowId: Int,
    initialPhotos: List<File>,
) : HomeWidgetHostView(context) {

    private val photos = initialPhotos.toMutableList()

    /** Seconds between photos while the home screen is showing; 0 changes it only on returning home. */
    var intervalSeconds = 0
        set(value) {
            field = value
            scheduleNext()
        }

    var order = SlideshowOrder.OLDEST_FIRST

    /** Called with the stored copy of the photo showing when it's tapped. */
    var onOpenPhoto: ((File) -> Unit)? = null

    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setOnClickListener { currentPhoto?.let { onOpenPhoto?.invoke(it) } }
    }
    private var currentPhoto: File? = null
    // Photos shown in this round, by name
    private val seen = Slideshows.seenPhotos(context, slideshowId)
    // Where an ordered slideshow continues from after a restart
    private var lastShownName = Slideshows.lastSeenPhoto(context, slideshowId)
    // Photos shown, so Previous can go back through them, kept while the launcher runs
    private val history = histories.getOrPut(slideshowId) { History() }
    private var running = false
    private var loading: Future<*>? = null
    private val advance = Runnable { showNext() }

    init {
        // The same rounded corners as other widgets
        val radius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        else 16 * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true

        if (photos.isEmpty()) {
            showEmpty()
        } else {
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val saved = shown[slideshowId]?.takeIf { it.photo in photos }
            if (saved != null) {
                currentPhoto = saved.photo
                image.setImageBitmap(saved.bitmap)
                rememberShowing()
            } else {
                // Once laid out, so the first photo is loaded at the right size
                post { showNext() }
            }
        }
    }

    private fun showEmpty() {
        removeAllViews()
        addView(TextView(context).apply {
            setText(R.string.slideshow_empty)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /**
     * Drops a photo that was deleted, moving on to the next one if it was showing.
     * Returns the photo shown next, or null when it wasn't the one showing or none are left.
     */
    fun removePhoto(photo: File): File? {
        photos.remove(photo)
        val inHistory = history.photos.indexOf(photo)
        if (inHistory >= 0) {
            history.photos.removeAt(inHistory)
            if (inHistory <= history.index) history.index--
        }
        if (photos.isEmpty()) {
            shown.remove(slideshowId)
            showEmpty()
            return null
        }
        return if (photo == currentPhoto) showNext() else null
    }

    // Not an app widget, so there's no app to tell its size
    override fun updateSize(widthDp: Int, heightDp: Int) = Unit

    /** The home screen is showing: the next photo comes a full interval from now. */
    fun resume() {
        running = true
        scheduleNext()
    }

    fun pause() {
        running = false
        removeCallbacks(advance)
    }

    /** Moves on to the next photo now, as the timer would, and returns it. */
    fun showNextPhoto(): File? = showNext()

    /** Whether [showPreviousPhoto] has a photo to go back to. */
    fun hasPreviousPhoto(): Boolean =
        photos.size > 1 && (history.index > 0 || order != SlideshowOrder.RANDOM)

    /**
     * Goes back to the photo shown before, and returns it. Before the first one shown, a slideshow in
     * date order goes on to earlier photos; a random one has nothing further back.
     */
    fun showPreviousPhoto(): File? {
        if (photos.size < 2) return null
        rememberShowing()
        val file = if (history.index > 0) {
            history.photos[--history.index]
        } else {
            val previous = previousByDate() ?: return null
            history.photos.add(0, previous)
            history.index = 0
            previous
        }
        show(file, crossfade = true)
        return file
    }

    /** The home screen is out of view: switch now, without a fade, so it's new on return. */
    fun showNextUnseen() {
        showNext(crossfade = false)
    }

    private fun scheduleNext() {
        removeCallbacks(advance)
        if (running && intervalSeconds > 0) postDelayed(advance, intervalSeconds * 1000L)
    }

    // A random photo not shown yet this round, or the next by date; once all were shown, a new round starts
    private fun chooseNext(): File? {
        if (photos.isEmpty()) return null
        if (order != SlideshowOrder.RANDOM) return nextByDate()
        var unseen = photos.filter { it.name !in seen && it != currentPhoto }
        if (unseen.isEmpty()) {
            seen.clear()
            Slideshows.startNewRound(context, slideshowId)
            unseen = photos.filter { it != currentPhoto }.ifEmpty { photos }
        }
        return unseen.random().also { markSeen(it) }
    }

    // Photos are stored numbered from oldest to newest, so their names give the order
    private fun nextByDate(): File {
        val last = currentPhoto?.name ?: lastShownName
        val next = when (order) {
            SlideshowOrder.NEWEST_FIRST -> photos.filter { last == null || it.name < last }.maxByOrNull { it.name }
            else -> photos.filter { last == null || it.name > last }.minByOrNull { it.name }
        }
        val chosen = next ?: run {
            // Past the end: start over from the beginning
            seen.clear()
            Slideshows.startNewRound(context, slideshowId)
            if (order == SlideshowOrder.NEWEST_FIRST) photos.maxBy { it.name } else photos.minBy { it.name }
        }
        return chosen.also { markSeen(it) }
    }

    // The photo before the one showing in date order, going round from the first to the last
    private fun previousByDate(): File? {
        val current = currentPhoto?.name ?: return null
        return when (order) {
            SlideshowOrder.RANDOM -> null
            SlideshowOrder.NEWEST_FIRST -> photos.filter { it.name > current }.minByOrNull { it.name }
                ?: photos.minByOrNull { it.name }
            SlideshowOrder.OLDEST_FIRST -> photos.filter { it.name < current }.maxByOrNull { it.name }
                ?: photos.maxByOrNull { it.name }
        }
    }

    private fun markSeen(photo: File) {
        seen += photo.name
        lastShownName = photo.name
        Slideshows.markSeen(context, slideshowId, photo)
    }

    private fun showNext(crossfade: Boolean = true): File? {
        rememberShowing()
        val file = if (history.index < history.photos.lastIndex) {
            // After going back, Next first goes forward again through what was shown
            history.photos[++history.index]
        } else {
            val chosen = chooseNext() ?: return null
            history.photos += chosen
            if (history.photos.size > HISTORY_SIZE) history.photos.removeAt(0)
            history.index = history.photos.lastIndex
            chosen
        }
        show(file, crossfade)
        return file
    }

    // Starts the history with the photo showing, when it has none yet
    private fun rememberShowing() {
        val showing = currentPhoto ?: return
        if (history.photos.isNotEmpty()) return
        history.photos += showing
        history.index = 0
    }

    private fun show(file: File, crossfade: Boolean) {
        // Straight away, so a tap while it loads opens the photo on its way in
        currentPhoto = file
        val targetWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetHeight = height.takeIf { it > 0 } ?: targetWidth
        loading?.cancel(false)
        loading = loader.submit(Runnable {
            val bitmap = Slideshows.loadPhoto(file, targetWidth, targetHeight) ?: return@Runnable
            post {
                if (crossfade) changeWithCrossfade { image.setImageBitmap(bitmap) }
                else image.setImageBitmap(bitmap)
                shown[slideshowId] = Shown(file, bitmap)
            }
        })
        scheduleNext()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(advance)
        loading?.cancel(true)
        super.onDetachedFromWindow()
    }

    private class Shown(val photo: File, val bitmap: Bitmap)

    private class History(val photos: MutableList<File> = mutableListOf(), var index: Int = -1)

    companion object {
        private const val HISTORY_SIZE = 200

        // Photos are decoded one at a time, off the main thread
        private val loader = Executors.newSingleThreadExecutor()

        // What each slideshow last showed and showed before, by its id, kept while the launcher runs
        private val shown = HashMap<Int, Shown>()
        private val histories = HashMap<Int, History>()

        /** Starts slideshows afresh, e.g. after their photos were replaced. */
        fun forgetShown() {
            shown.clear()
            histories.clear()
        }
    }
}
