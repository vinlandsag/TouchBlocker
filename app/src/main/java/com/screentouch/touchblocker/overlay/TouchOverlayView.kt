package com.screentouch.touchblocker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Core Touch Overlay View.
 *
 * Extends [View] and intercepts all touch input within its bounds by returning true
 * in [onTouchEvent], preventing any touches from reaching underlying windows or apps.
 *
 * Supports configurable [overlayColor] and [overlayAlpha] for visual debug shading
 * (e.g. semi-transparent red rectangle) or invisible blocking.
 */
class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /**
     * Overlay color for the blocking zone.
     * Defaults to red for visual debug shading.
     */
    var overlayColor: Int = Color.RED
        set(value) {
            field = value
            updatePaint()
            invalidate()
        }

    /**
     * Overlay alpha transparency (0.0f = completely invisible, 1.0f = fully opaque).
     * Defaults to 0.35f (semi-transparent red for debug visibility).
     */
    var overlayAlpha: Float = 0.35f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updatePaint()
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = false
        updatePaint()
    }

    private fun updatePaint() {
        paint.color = overlayColor
        paint.alpha = (overlayAlpha * 255).toInt().coerceIn(0, 255)
    }

    /**
     * Consumes all MotionEvents (ACTION_DOWN, ACTION_MOVE, ACTION_UP, ACTION_CANCEL, etc.)
     * completely inside this view's bounds to block touches from reaching underlying views.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Return true for all MotionEvents to consume them completely
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (overlayAlpha > 0f) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
