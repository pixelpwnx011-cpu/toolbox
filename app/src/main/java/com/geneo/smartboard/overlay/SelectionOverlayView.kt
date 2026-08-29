package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Lets the user drag a rectangle over the PDF page to select a word/phrase
 * for lookup — drawn as a translucent green highlight while dragging. Only
 * visible/touchable while select mode is toggled on; sits above the PDF view
 * in the layout so it gets first pick of touches when shown.
 */
class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val fillPaint = Paint().apply { color = Color.parseColor("#6034C759") }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34C759")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** Fired once the user lifts their finger, with the selected rect in this view's own coordinates. */
    var onSelectionComplete: ((RectF) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                curX = event.x
                curY = event.y
                dragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val rect = currentRect()
                invalidate()
                if (event.action == MotionEvent.ACTION_UP && rect.width() > 16 && rect.height() > 16) {
                    onSelectionComplete?.invoke(rect)
                }
            }
        }
        return true
    }

    private fun currentRect(): RectF = RectF(
        minOf(startX, curX), minOf(startY, curY),
        maxOf(startX, curX), maxOf(startY, curY)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dragging) {
            val rect = currentRect()
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
        }
    }
}
