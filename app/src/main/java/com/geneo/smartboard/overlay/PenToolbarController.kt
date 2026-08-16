package com.geneo.smartboard.overlay

import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Wires the pen toolbar's buttons to a PenCanvasView, and makes the toolbar
 * itself draggable (by its grip handle) so it can be moved out of the way
 * without leaving the pen tool.
 */
class PenToolbarController(root: View, private val canvas: PenCanvasView) {

    private val toolbar: View = root.findViewById(R.id.penToolbar)
    private val dragHandle: View = root.findViewById(R.id.penDragHandle)
    private val tvSize: TextView = root.findViewById(R.id.tvPenSize)

    private val btnTypePen: ImageButton = root.findViewById(R.id.btnTypePen)
    private val btnTypeHighlighter: ImageButton = root.findViewById(R.id.btnTypeHighlighter)
    private val btnEraser: ImageButton = root.findViewById(R.id.btnEraser)
    private val typeButtons = listOf(btnTypePen, btnTypeHighlighter)
    private var lastActiveType: ImageButton = btnTypePen

    private val swatches: List<Pair<View, Int>>
    private var selectedSwatch: View? = null

    init {
        btnTypePen.setOnClickListener { selectType(PenType.PEN, btnTypePen) }
        btnTypeHighlighter.setOnClickListener { selectType(PenType.HIGHLIGHTER, btnTypeHighlighter) }

        val swatchRed = root.findViewById<View>(R.id.swatchRed)
        val swatchYellow = root.findViewById<View>(R.id.swatchYellow)
        val swatchGreen = root.findViewById<View>(R.id.swatchGreen)
        val swatchBlue = root.findViewById<View>(R.id.swatchBlue)
        val swatchWhite = root.findViewById<View>(R.id.swatchWhite)

        swatches = listOf(
            swatchRed to 0xFFFF3B30.toInt(),
            swatchYellow to 0xFFFFD60A.toInt(),
            swatchGreen to 0xFF34C759.toInt(),
            swatchBlue to 0xFF0A84FF.toInt(),
            swatchWhite to 0xFFFFFFFF.toInt()
        )
        swatches.forEach { (view, color) -> view.setOnClickListener { selectColor(view, color) } }

        root.findViewById<ImageButton>(R.id.btnSizeMinus).setOnClickListener {
            canvas.adjustActiveSize(if (canvas.isErasing()) -6 else -2)
            updateSizeLabel()
        }
        root.findViewById<ImageButton>(R.id.btnSizePlus).setOnClickListener {
            canvas.adjustActiveSize(if (canvas.isErasing()) 6 else 2)
            updateSizeLabel()
        }

        btnEraser.setOnClickListener {
            canvas.setEraseMode()
            setActiveTypeButton(null)
            updateSizeLabel()
        }

        root.findViewById<ImageButton>(R.id.btnClearPen).setOnClickListener { canvas.clearAll() }

        selectType(PenType.PEN, btnTypePen) // default type
        selectColor(swatchRed, 0xFFFF3B30.toInt()) // default color, matches PenCanvasView's default
        updateSizeLabel()
        setupDrag()
    }

    private fun selectType(type: PenType, button: ImageButton) {
        canvas.setPenType(type)
        lastActiveType = button
        setActiveTypeButton(button)
        updateSizeLabel()
    }

    private fun selectColor(swatchView: View, color: Int) {
        canvas.setColor(color)
        setActiveTypeButton(lastActiveType)
        highlightSwatch(swatchView)
        updateSizeLabel()
    }

    private fun highlightSwatch(swatchView: View) {
        selectedSwatch?.foreground = null
        swatchView.foreground = ContextCompat.getDrawable(swatchView.context, R.drawable.shape_circle_ring)
        selectedSwatch = swatchView
    }

    private fun setActiveTypeButton(button: ImageButton?) {
        typeButtons.forEach { it.alpha = 0.5f }
        button?.alpha = 1f
        btnEraser.alpha = if (button == null) 1f else 0.5f
    }

    private fun updateSizeLabel() {
        tvSize.text = canvas.activeSizeDp().toString()
    }

    private fun setupDrag() {
        var startX = 0f
        var startY = 0f
        var startTouchX = 0f
        var startTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = toolbar.x
                    startY = toolbar.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    toolbar.x = startX + dx
                    toolbar.y = (startY + dy).coerceAtLeast(0f)
                    true
                }
                else -> true
            }
        }
    }
}
