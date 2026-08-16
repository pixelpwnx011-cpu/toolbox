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
 *
 * Pen, Highlighter, and Eraser each keep their OWN size independently —
 * switching between them (or picking a color, which doesn't touch size at
 * all) never resets another tool's size. Sizes start at each preset's
 * sensible default but only change when the user actually adjusts them.
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
    private var currentPenType = PenType.PEN

    // Independent, per-tool size state — this is the fix: previously a single
    // shared width field got overwritten to the preset default every time
    // setPenType() ran, so switching tools (or switching away and back)
    // silently discarded any manual size adjustment.
    private val penTypeWidths = mutableMapOf(
        PenType.PEN to PenType.PEN.widthDp,
        PenType.HIGHLIGHTER to PenType.HIGHLIGHTER.widthDp
    )
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
        currentPenType = type
        mode = Mode.DRAW
        // Intentionally does NOT touch penTypeWidths — size stays whatever it
        // was last set to for this specific type.
    }

    fun setEraseMode() {
        mode = Mode.ERASE
    }

    fun isErasing(): Boolean = mode == Mode.ERASE

    fun adjustActiveSize(deltaDp: Int) {
        if (mode == Mode.ERASE) {
            eraserWidthDp = (eraserWidthDp + deltaDp).coerceIn(10, 90)
        } else {
            val current = penTypeWidths[currentPenType] ?: currentPenType.widthDp
            penTypeWidths[currentPenType] = (current + deltaDp).coerceIn(2, 40)
        }
    }

    /** Current stroke width in dp for whichever mode/type is active right now. */
    fun activeSizeDp(): Int =
        if (mode == Mode.ERASE) eraserWidthDp else (penTypeWidths[currentPenType] ?: currentPenType.widthDp)

    fun clearAll() {
        strokes.clear()
        currentPath = null
        invalidate()
    }

    // --- Size persistence across closing/reopening the pen tool ---

    fun penSizeDp(): Int = penTypeWidths[PenType.PEN] ?: PenType.PEN.widthDp
    fun highlighterSizeDp(): Int = penTypeWidths[PenType.HIGHLIGHTER] ?: PenType.HIGHLIGHTER.widthDp
    fun eraserSizeDp(): Int = eraserWidthDp

    /** Restores sizes remembered from the last time the pen tool was used this session. */
    fun restoreSizes(penDp: Int, highlighterDp: Int, eraserDp: Int) {
        penTypeWidths[PenType.PEN] = penDp.coerceIn(2, 40)
        penTypeWidths[PenType.HIGHLIGHTER] = highlighterDp.coerceIn(2, 40)
        eraserWidthDp = eraserDp.coerceIn(10, 90)
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
            p.alpha = currentPenType.alpha
            p.strokeWidth = dp(penTypeWidths[currentPenType] ?: currentPenType.widthDp)
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
