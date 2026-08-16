package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class PenType(val widthDp: Int, val alpha: Int) {
    PEN(5, 255),
    HIGHLIGHTER(20, 90)
}

/**
 * Full-screen freehand drawing/annotation layer. Works over anything drawn
 * beneath it (any app, or Geneo Toolbox's own PDF viewer) since it's just
 * the topmost overlay window while active — no special integration needed.
 *
 * Kept lightweight on purpose: strokes are plain Path objects in a list, no
 * bitmap caching or smoothing — perfectly fine for a normal annotation
 * session and much simpler/cheaper than a bitmap-backed canvas.
 */
class PenCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Stroke(val path: Path, val paint: Paint)

    private val strokes = mutableListOf<Stroke>()
    private var currentPath: Path? = null
    private var currentPaint: Paint = freshPaint()

    private var mode = Mode.DRAW
    private var color = Color.parseColor("#FF3B30") // default red
    private var penWidthDp = PenType.PEN.widthDp
    private var penAlpha = PenType.PEN.alpha
    private var eraserWidthDp = 26

    enum class Mode { DRAW, ERASE }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // required for PorterDuff.CLEAR eraser to work
        setBackgroundColor(Color.TRANSPARENT) // draws directly over whatever is on screen — never opaque
    }

    // --- Public controls, called by the toolbar ---

    fun setColor(newColor: Int) {
        color = newColor
        mode = Mode.DRAW
    }

    fun setPenType(type: PenType) {
        penWidthDp = type.widthDp
        penAlpha = type.alpha
        mode = Mode.DRAW
    }

    fun setEraseMode() {
        mode = Mode.ERASE
    }

    fun isErasing(): Boolean = mode == Mode.ERASE

    fun adjustActiveSize(deltaDp: Int) {
        if (mode == Mode.ERASE) {
            eraserWidthDp = (eraserWidthDp + deltaDp).coerceIn(10, 90)
        } else {
            penWidthDp = (penWidthDp + deltaDp).coerceIn(2, 40)
        }
    }

    /** Current stroke width in dp for whichever mode (pen or eraser) is active right now. */
    fun activeSizeDp(): Int = if (mode == Mode.ERASE) eraserWidthDp else penWidthDp

    fun clearAll() {
        strokes.clear()
        currentPath = null
        invalidate()
    }

    // --- Drawing ---

    private fun freshPaint(): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        return p
    }

    private fun paintForCurrentMode(): Paint {
        val p = freshPaint()
        if (mode == Mode.ERASE) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            p.strokeWidth = dp(eraserWidthDp)
        } else {
            p.color = color
            p.alpha = penAlpha
            p.strokeWidth = dp(penWidthDp)
        }
        return p
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        for (stroke in strokes) canvas.drawPath(stroke.path, stroke.paint)
        currentPath?.let { canvas.drawPath(it, currentPaint) }
        canvas.restoreToCount(layerId)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path()
                path.moveTo(event.x, event.y)
                currentPath = path
                currentPaint = paintForCurrentMode()
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.let { strokes.add(Stroke(it, currentPaint)) }
                currentPath = null
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
