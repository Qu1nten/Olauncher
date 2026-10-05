package app.olauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.RemoteViews
import androidx.core.animation.doOnEnd
import app.olauncher.data.Constants
import app.olauncher.data.WidgetTaps
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CROSSFADE_MS = 700L

class HomeWidgetHost(context: Context) : AppWidgetHost(context, Constants.HOME_WIDGET_HOST_ID) {

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        HomeWidgetHostView(context)
}

/**
 * Widget view that reports a long press anywhere on the widget, even over the
 * widget's own buttons, so it can be edited. Keeping the finger down after the
 * long press drags the widget: [onDrag] gets the vertical distance moved.
 */
class HomeWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null
    var onDrag: ((dy: Float) -> Unit)? = null
    var onDragEnd: ((dy: Float) -> Unit)? = null

    /** The home screen's gesture handling (swipes, double tap, long press for settings). */
    var homeGestures: ((MotionEvent) -> Unit)? = null
    /** Tells the home screen to drop the gesture it was handed, because the widget took it over. */
    var onHomeGestureCancel: (() -> Unit)? = null

    /**
     * Unless [WidgetTaps.ON], no touch reaches the widget's own views and every gesture also goes
     * to the home screen, as if the widget weren't there; with [WidgetTaps.DOUBLE_TAP] a double tap
     * is then passed on to the widget as a tap. Long press still selects it for editing.
     */
    var taps = WidgetTaps.ON

    // Decided when a touch starts, so switching settings mid-gesture can't half-forward it
    private var forwardingHome = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawY = 0f
    private var hasPerformedLongPress = false
    private var movedBeyondSlop = false

    // Double tap: when and where the last plain tap ended, and whether this touch is the second tap
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var isSecondTap = false
    // Set while a double tap is replayed to the widget as a single tap
    private var deliveringTap = false

    private val longPressRunnable = Runnable {
        hasPerformedLongPress = true
        onLongPress?.let {
            // The widget is being edited now, so the home screen mustn't also act on this touch
            if (forwardingHome) {
                forwardingHome = false
                onHomeGestureCancel?.invoke()
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            it()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            isSecondTap = taps == WidgetTaps.DOUBLE_TAP &&
                    ev.eventTime - lastTapUpTime <= ViewConfiguration.getDoubleTapTimeout() &&
                    abs(ev.x - lastTapX) <= doubleTapSlop && abs(ev.y - lastTapY) <= doubleTapSlop
            forwardingHome = taps != WidgetTaps.ON && !isSecondTap
            // The second tap belongs to the widget, so the home screen mustn't see a double tap (lock)
            if (isSecondTap) onHomeGestureCancel?.invoke()
        }
        if (forwardingHome) homeGestures?.invoke(ev)

        val wasPlainTap = !movedBeyondSlop && !hasPerformedLongPress
        val handled = super.dispatchTouchEvent(ev)
        if (taps == WidgetTaps.DOUBLE_TAP) trackDoubleTap(ev, wasPlainTap)
        return handled
    }

    private fun trackDoubleTap(ev: MotionEvent, wasPlainTap: Boolean) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> when {
                !wasPlainTap -> lastTapUpTime = 0L
                isSecondTap -> {
                    lastTapUpTime = 0L
                    val x = ev.x
                    val y = ev.y
                    // After this touch has finished dispatching
                    post { tapWidget(x, y) }
                }

                else -> {
                    lastTapUpTime = ev.eventTime
                    lastTapX = ev.x
                    lastTapY = ev.y
                }
            }

            MotionEvent.ACTION_CANCEL -> lastTapUpTime = 0L
        }
    }

    // Replays a tap at this point to the widget's own views, which do whatever a tap does there
    private fun tapWidget(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, x, y, 0)
        deliveringTap = true
        try {
            super.dispatchTouchEvent(down)
            super.dispatchTouchEvent(up)
        } finally {
            deliveringTap = false
            down.recycle()
            up.recycle()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (deliveringTap) return false
        trackLongPress(ev)
        // Once the long press fired, take over the gesture so the widget doesn't also get a click
        return taps != WidgetTaps.ON || hasPerformedLongPress
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (deliveringTap) return true
        // Reached when no child handles the touch, or after the long press took over the gesture.
        // Keep receiving events so long press works on empty areas and the widget can be dragged.
        trackLongPress(ev)
        if (hasPerformedLongPress) {
            // Raw coordinates, since the view itself moves while being dragged
            val dy = ev.rawY - downRawY
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> onDrag?.invoke(dy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hasPerformedLongPress = false
                    onDragEnd?.invoke(dy)
                }
            }
        }
        return true
    }

    private fun trackLongPress(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hasPerformedLongPress = false
                movedBeyondSlop = false
                downX = ev.x
                downY = ev.y
                downRawY = ev.rawY
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    movedBeyondSlop = true
                    removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                removeCallbacks(longPressRunnable)
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        endCrossfade()
        super.onDetachedFromWindow()
    }

    // Crossfade: a snapshot of what the widget showed before its last update, faded out on top
    private var fadeSnapshot: Bitmap? = null
    private var fadeAnimator: ValueAnimator? = null
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        val snapshot = snapshotContent()
        super.updateAppWidget(remoteViews)
        if (snapshot != null) startCrossfade(snapshot)
    }

    private fun snapshotContent(): Bitmap? {
        // Nothing to fade from on the first update, or while the widget isn't on screen
        if (!isAttachedToWindow || width == 0 || height == 0 || childCount == 0) return null
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startCrossfade(snapshot: Bitmap) {
        endCrossfade()
        fadeSnapshot = snapshot
        fadeAnimator = ValueAnimator.ofInt(255, 0).apply {
            duration = CROSSFADE_MS
            addUpdateListener {
                fadePaint.alpha = it.animatedValue as Int
                invalidate()
            }
            doOnEnd { endCrossfade() }
            start()
        }
    }

    private fun endCrossfade() {
        fadeAnimator?.let {
            fadeAnimator = null
            it.cancel()
        }
        fadeSnapshot?.recycle()
        fadeSnapshot = null
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        fadeSnapshot?.let { canvas.drawBitmap(it, 0f, 0f, fadePaint) }
    }

    /**
     * Asks the widget's app to update it now, the same request Android sends on the widget's own
     * update schedule. The app decides whether that shows anything new.
     */
    fun requestUpdate() {
        val info = appWidgetInfo ?: return
        try {
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Tells the widget how much space it has, so it can pick a fitting layout. */
    fun updateSize(widthDp: Int, heightDp: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
        else
            @Suppress("DEPRECATION")
            updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
    }
}

/** Height a newly added widget starts with, based on the size its provider asks for. */
fun AppWidgetProviderInfo.defaultHeightDp(context: Context): Int {
    val density = context.resources.displayMetrics.density
    val minHeightDp = (minHeight / density).roundToInt()
    val maxHeightDp = context.resources.configuration.screenHeightDp / 2
    return minHeightDp.coerceIn(Constants.WIDGET_MIN_HEIGHT_DP, maxOf(Constants.WIDGET_MIN_HEIGHT_DP, maxHeightDp))
}
