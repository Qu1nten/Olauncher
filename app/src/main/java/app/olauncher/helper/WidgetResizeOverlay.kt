package app.olauncher.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Drawn over a widget while it's being edited: a dashed outline with a handle on the
 * bottom edge (height) and one on the side edge (width). The widget underneath gets no
 * touches meanwhile. Dragging a handle reports how much the widget should grow since the
 * drag started; dragging anywhere else reports how far the widget was moved vertically.
 */
class WidgetResizeOverlay(context: Context) : View(context) {

    /** Called while dragging with the growth in px since the drag started; negative shrinks. */
    var onResize: ((growWidth: Float, growHeight: Float) -> Unit)? = null
    var onResizeEnd: (() -> Unit)? = null
    var onMove: ((dy: Float) -> Unit)? = null
    var onMoveEnd: ((dy: Float) -> Unit)? = null

    /** Matches the outline to the widget's rounded corners, see [widgetCornerRadius]. */
    var cornerPercent = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Puts the width handle on the start edge, for widgets aligned to the end of the screen. */
    var widthHandleOnStart = false
        set(value) {
            field = value
            invalidate()
        }

    var color: Int = Color.WHITE
        set(value) {
            field = value
            borderPaint.color = value
            handlePaint.color = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val handleTouchSize = 44 * density
    private val rect = RectF()

    private val dimPaint = Paint().apply { color = 0x40000000 }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var dragWidth = false
    private var dragHeight = false
    private var moving = false
    private var startRawX = 0f
    private var startRawY = 0f

    private val widthHandleOnLeft get() = widthHandleOnStart != (layoutDirection == LAYOUT_DIRECTION_RTL)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = borderPaint.strokeWidth / 2
        val radius = widgetCornerRadius(width, height, cornerPercent)
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius, radius, dimPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val long = 20 * density
        val thick = 3 * density
        val edge = 6 * density
        // Height handle, centered on the bottom edge
        rect.set(w / 2 - long, h - edge - 2 * thick, w / 2 + long, h - edge)
        canvas.drawRoundRect(rect, thick, thick, handlePaint)
        // Width handle, centered on the side edge
        val x = if (widthHandleOnLeft) edge else w - edge - 2 * thick
        rect.set(x, h / 2 - long.coerceAtMost(h / 4), x + 2 * thick, h / 2 + long.coerceAtMost(h / 4))
        canvas.drawRoundRect(rect, thick, thick, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragHeight = event.y > height - handleTouchSize
                dragWidth = if (widthHandleOnLeft) event.x < handleTouchSize else event.x > width - handleTouchSize
                // Raw coordinates, since the view itself changes size during the drag
                startRawX = event.rawX
                startRawY = event.rawY
                moving = !dragWidth && !dragHeight
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (moving) {
                    onMove?.invoke(event.rawY - startRawY)
                    return true
                }
                val dx = event.rawX - startRawX
                val growWidth = if (!dragWidth) 0f else if (widthHandleOnLeft) -dx else dx
                val growHeight = if (dragHeight) event.rawY - startRawY else 0f
                onResize?.invoke(growWidth, growHeight)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moving) onMoveEnd?.invoke(event.rawY - startRawY)
                else onResizeEnd?.invoke()
                dragWidth = false
                dragHeight = false
                moving = false
            }
        }
        // Swallow everything so the widget underneath can't be tapped while editing
        return true
    }
}

/** Corner radius for a widget of this size: [percent] of the way to fully rounded short sides. */
fun widgetCornerRadius(width: Int, height: Int, percent: Int): Float =
    minOf(width, height) / 2f * percent.coerceIn(0, 100) / 100f
