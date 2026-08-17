package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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
 * PERFORMANCE: this used to lag badly, for two reasons, both fixed here:
 * 1. The whole view ran in LAYER_TYPE_SOFTWARE (CPU-only rendering,
 *    disabling the GPU entirely) just so the eraser's transparency-punch
 *    would work. Removed — completed strokes are now baked into their own
 *    dedicated Bitmap-backed Canvas, which always supports that correctly
 *    regardless of the view's own rendering mode, so the view itself is
 *    fully hardware-accelerated again.
 * 2. Every frame while drawing replayed the ENTIRE stroke history from
 *    scratch — the longer a session went, the slower every new stroke got.
 *    Completed strokes are now "baked" into a bitmap the moment they're
 *    finished, so redrawing old content is one cheap bitmap blit no matter
 *    how much has been drawn. Only the CURRENT in-progress stroke is drawn
 *    live each frame, and only the eraser (which needs the transparency
 *    trick) uses a saveLayer — scoped to just that stroke's small bounding
 *    box, not the full screen.
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

    private var bakedBitmap: Bitmap? = null
    private var bakedCanvas: Canvas? = null

    private var currentPath: Path? = null
    private var currentPaint: Paint = freshPaint()
    private val liveBounds = RectF()

    private var mode = Mode.DRAW
    private var color = Color.parseColor("#FF3B30") // default red
    private var currentPenType = PenType.PEN

    // Independent, per-tool size state — switching tools (or switching away
    // and back) never resets another tool's size.
    private val penTypeWidths = mutableMapOf(
        PenType.PEN to PenType.PEN.widthDp,
        PenType.HIGHLIGHTER to PenType.HIGHLIGHTER.widthDp
    )
    private var eraserWidthDp = 26

    enum class Mode { DRAW, ERASE }

    init {
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
        bakedBitmap?.eraseColor(Color.TRANSPARENT)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val old = bakedBitmap
        if (old != null && old.width == w && old.height == h) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        old?.let { c.drawBitmap(it, 0f, 0f, null) } // keep existing drawing if the window was resized
        old?.recycle()
        bakedBitmap = bmp
        bakedCanvas = c
    }

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
        bakedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        val path = currentPath ?: return
        if (mode == Mode.ERASE) {
            // Only the eraser needs the transparency-punch trick, and only for
            // the small area its stroke actually covers — not the full screen.
            val baked = bakedBitmap ?: return
            path.computeBounds(liveBounds, true)
            val pad = currentPaint.strokeWidth
            liveBounds.inset(-pad, -pad)
            val layerId = canvas.saveLayer(liveBounds, null)
            canvas.drawBitmap(baked, 0f, 0f, null)
            canvas.drawPath(path, currentPaint)
            canvas.restoreToCount(layerId)
        } else {
            canvas.drawPath(path, currentPaint)
        }
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
                currentPath?.let { bakedCanvas?.drawPath(it, currentPaint) }
                currentPath = null
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
