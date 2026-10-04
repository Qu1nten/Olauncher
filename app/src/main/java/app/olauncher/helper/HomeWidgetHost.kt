package app.olauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.olauncher.data.Constants
import kotlin.math.abs
import kotlin.math.roundToInt

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

    /**
     * Set for a fullscreen widget: every touch is also handed to the home screen's gesture
     * handling, and only plain taps reach the widget, so swipes and long press work as usual.
     */
    var homeGestures: ((MotionEvent) -> Unit)? = null

    /** When false, no touch reaches the widget's own views; long press and dragging still work. */
    var interactive = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawY = 0f
    private var hasPerformedLongPress = false
    private var movedBeyondSlop = false

    private val longPressRunnable = Runnable {
        hasPerformedLongPress = true
        onLongPress?.let {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            it()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        homeGestures?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        trackLongPress(ev)
        // Once the long press fired, take over the gesture so the widget doesn't also get a click.
        // A fullscreen widget also gives up swipes, which belong to the home screen.
        return !interactive || hasPerformedLongPress || (homeGestures != null && movedBeyondSlop)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
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
        super.onDetachedFromWindow()
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
