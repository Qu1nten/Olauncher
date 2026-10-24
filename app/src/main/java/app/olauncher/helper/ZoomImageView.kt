package app.olauncher.helper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An image shown whole, which can be pinched to zoom in and dragged around while zoomed.
 * Double tap zooms in on that spot, or back out; a single tap counts as a click.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val bounds = RectF()
    // How far zoomed in from showing the whole photo
    private var zoom = 1f
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f) {
                drawMatrix.postTranslate(-distanceX, -distanceY)
                applyMatrix()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoomTo(if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        showWhole()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        showWhole()
    }

    // Fits the whole photo in the view, centered
    private fun showWhole() {
        val photo = drawable ?: return
        if (width == 0 || height == 0 || photo.intrinsicWidth <= 0 || photo.intrinsicHeight <= 0) return
        val fit = minOf(width.toFloat() / photo.intrinsicWidth, height.toFloat() / photo.intrinsicHeight)
        zoom = 1f
        drawMatrix.setScale(fit, fit)
        drawMatrix.postTranslate(
            (width - photo.intrinsicWidth * fit) / 2,
            (height - photo.intrinsicHeight * fit) / 2
        )
        imageMatrix = drawMatrix
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val change = newZoom / zoom
        zoom = newZoom
        drawMatrix.postScale(change, change, focusX, focusY)
        applyMatrix()
    }

    private fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 250
            addUpdateListener { zoomBy(it.animatedValue as Float / zoom, focusX, focusY) }
            start()
        }
    }

    // Keeps the photo covering the view where it's larger than it, and centered where it's smaller
    private fun applyMatrix() {
        val photo = drawable ?: return
        bounds.set(0f, 0f, photo.intrinsicWidth.toFloat(), photo.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        drawMatrix.postTranslate(correction(bounds.left, bounds.right, width), correction(bounds.top, bounds.bottom, height))
        imageMatrix = drawMatrix
    }

    private fun correction(start: Float, end: Float, size: Int): Float = when {
        end - start <= size -> (size - (end - start)) / 2 - start
        start > 0 -> -start
        end < size -> size - end
        else -> 0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        zoomAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
