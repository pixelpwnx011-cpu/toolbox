package com.geneo.smartboard.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Attaches a touch listener to [dragHandleView] that repositions [targetView]'s
 * WindowManager params (assumed to belong to the same overlay window). Distinguishes
 * a plain tap from a drag using [touchSlopPx], so a quick tap still fires [onTap]
 * while a press-and-move repositions the window.
 */
@SuppressLint("ClickableViewAccessibility")
class DragHelper(
    private val windowManager: WindowManager,
    private val targetView: View,
    private val params: WindowManager.LayoutParams,
    private val touchSlopPx: Int,
    private val onTap: () -> Unit,
    private val onDragStart: () -> Unit = {},
    private val onDragEnd: (finalX: Int, finalY: Int) -> Unit = { _, _ -> }
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragging = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX)
                val dy = (event.rawY - initialTouchY)
                if (!dragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                    dragging = true
                    onDragStart()
                }
                if (dragging) {
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(targetView, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    onDragEnd(params.x, params.y)
                } else {
                    onTap()
                }
                return true
            }
        }
        return false
    }
}
