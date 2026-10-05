package app.olauncher.helper

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import app.olauncher.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The launcher's own photo slideshow widget. It shares the touch handling of app widgets
 * (editing, dragging, the tap settings) and shows its photos in random order with a crossfade,
 * moving on every [intervalSeconds] while resumed, each time the home screen comes back, and on tap.
 */
class SlideshowView(context: Context, private val photos: List<File>) : HomeWidgetHostView(context) {

    /** Seconds between photos while the home screen is showing; 0 changes it only on returning home. */
    var intervalSeconds = 0
        set(value) {
            field = value
            scheduleNext()
        }

    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setOnClickListener { showNext() }
    }
    private var order = photos.shuffled()
    private var position = -1
    private var running = false
    private var wasPaused = false
    private var loading: Future<*>? = null
    private val advance = Runnable { showNext() }

    init {
        if (photos.isEmpty()) {
            addView(TextView(context).apply {
                setText(R.string.slideshow_empty)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } else {
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // Once laid out, so the first photo is loaded at the right size
            post { showNext() }
        }
    }

    // Not an app widget, so there's no app to tell its size
    override fun updateSize(widthDp: Int, heightDp: Int) = Unit

    /** The home screen is showing: coming back to it moves on to the next photo. */
    fun resume() {
        if (wasPaused) showNext()
        wasPaused = false
        running = true
        scheduleNext()
    }

    fun pause() {
        wasPaused = true
        running = false
        removeCallbacks(advance)
    }

    private fun scheduleNext() {
        removeCallbacks(advance)
        if (running && intervalSeconds > 0) postDelayed(advance, intervalSeconds * 1000L)
    }

    private fun showNext() {
        if (photos.isEmpty()) return
        position++
        if (position >= order.size) {
            // A new random order each round, not starting with the photo just shown
            val last = order.lastOrNull()
            order = photos.shuffled().let { if (it.size > 1 && it.first() == last) it.drop(1) + it.first() else it }
            position = 0
        }
        val file = order[position]
        val targetWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetHeight = height.takeIf { it > 0 } ?: targetWidth
        loading?.cancel(false)
        loading = loader.submit(Runnable {
            val bitmap = Slideshows.loadPhoto(file, targetWidth, targetHeight) ?: return@Runnable
            post { changeWithCrossfade { image.setImageBitmap(bitmap) } }
        })
        scheduleNext()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(advance)
        loading?.cancel(true)
        super.onDetachedFromWindow()
    }

    companion object {
        // Photos are decoded one at a time, off the main thread
        private val loader = Executors.newSingleThreadExecutor()
    }
}
