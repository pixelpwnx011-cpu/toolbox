package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Continuous, pinch-zoomable, pannable PDF page viewer — every page is laid
 * out in one tall vertical strip (like a normal document reader), so moving
 * between pages is just scrolling, not a "next/prev" button.
 *
 * Two things kept this laggy before and are fixed here:
 * 1. Resizing the window fired a full synchronous rebuild (clear cache,
 *    recompute every page's layout, re-render) on EVERY intermediate drag
 *    frame. Now that rebuild is debounced — it only actually runs ~180ms
 *    after resizing pauses, using the last-known bitmaps in the meantime.
 * 2. Page rendering happened synchronously on the main thread right when a
 *    scroll gesture ended, causing a visible freeze. Rendering now happens
 *    on a single background thread and results are posted back — the UI
 *    thread is never blocked waiting for a page to render.
 *
 * Still lightweight: only pages near the current viewport are ever rendered
 * to a bitmap; everything else is recycled as you scroll past it.
 */
class PdfContinuousView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class PageMeta(
        val widthPt: Float,
        val heightPt: Float,
        var yOffset: Float = 0f,
        var heightPx: Float = 0f
    )

    private var renderer: PdfRenderer? = null
    private val rendererLock = Any() // guards all PdfRenderer/Page access (main thread + render thread)
    private val pages = mutableListOf<PageMeta>()
    private var contentWidthPx = 0f
    private var contentHeightPx = 0f
    private var renderWidthPx = 900

    private val bitmapCache = HashMap<Int, Bitmap>()
    private val pendingRenders = HashSet<Int>()
    private val maxCachedPages = 6

    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var released = false

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var minScale = 1f
    private var maxScale = 4f
    private var initialized = false

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint().apply { color = Color.WHITE }
    private val gapPx = dp(8)

    // Debounces the expensive relayout+re-render that a window resize triggers.
    private val resizeSettleRunnable = Runnable {
        if (released || pages.isEmpty()) return@Runnable
        recomputeLayout()
        synchronized(bitmapCache) {
            bitmapCache.values.forEach { it.recycle() }
            bitmapCache.clear()
        }
        resetView()
    }

    /** Reports the page currently centered in the viewport, e.g. for a "Page 3 / 12" readout. */
    var onPageIndicatorChanged: ((current: Int, total: Int) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            clampMatrix()
            invalidate()
            return true
        }
    })

    fun attach(pdfRenderer: PdfRenderer) {
        // NOTE: no release() call here. attach() only ever runs on a fresh
        // instance (a new chapter open = a newly inflated view), so there is
        // nothing to release yet — and release() shuts down renderExecutor
        // permanently, which would kill background rendering before this
        // instance ever renders a single page.
        released = false
        renderer = pdfRenderer
        buildPageMetadata()
        if (width > 0) {
            recomputeLayout()
            resetView()
            initialized = true
        }
    }

    private fun buildPageMetadata() {
        pages.clear()
        val r = renderer ?: return
        synchronized(rendererLock) {
            for (i in 0 until r.pageCount) {
                val page = r.openPage(i)
                pages.add(PageMeta(page.width.toFloat(), page.height.toFloat()))
                page.close()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || pages.isEmpty()) return

        if (!initialized) {
            // First real layout pass after attach() ran before the view had a size yet.
            recomputeLayout()
            resetView()
            initialized = true
            return
        }

        // A genuine resize (corner-drag) — debounce the heavy work so dragging
        // stays smooth; the old bitmaps just get reused at the old scale/fit
        // until the drag pauses, then everything re-fits cleanly.
        mainHandler.removeCallbacks(resizeSettleRunnable)
        mainHandler.postDelayed(resizeSettleRunnable, 180L)
    }

    private fun recomputeLayout() {
        renderWidthPx = width.coerceIn(400, 1080)
        var y = 0f
        for (meta in pages) {
            val scale = renderWidthPx / meta.widthPt
            val hPx = meta.heightPt * scale
            meta.yOffset = y
            meta.heightPx = hPx
            y += hPx + gapPx
        }
        contentWidthPx = renderWidthPx.toFloat()
        contentHeightPx = (y - gapPx).coerceAtLeast(0f)
    }

    private fun resetView() {
        matrix.reset()
        if (contentWidthPx > 0 && width > 0) {
            val fitScale = width / contentWidthPx
            matrix.postScale(fitScale, fitScale)
            minScale = fitScale
            maxScale = fitScale * 4f
        }
        clampMatrix()
        invalidate()
        renderVisiblePages()
        reportVisiblePage()
    }

    /** Jumps directly to a page (used by the page-scrubber slider) at the current zoom level. */
    fun scrollToPage(index: Int) {
        if (index !in pages.indices) return
        val scale = currentScale()
        matrix.getValues(matrixValues)
        matrixValues[Matrix.MTRANS_Y] = -(pages[index].yOffset * scale)
        matrix.setValues(matrixValues)
        clampMatrix()
        invalidate()
        renderVisiblePages()
        reportVisiblePage()
    }

    // --- Touch: pinch to zoom, drag to pan (works with one or two fingers) ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val dx = event.getX(idx) - lastTouchX
                        val dy = event.getY(idx) - lastTouchY
                        matrix.postTranslate(dx, dy)
                        clampMatrix()
                        invalidate()
                        lastTouchX = event.getX(idx)
                        lastTouchY = event.getY(idx)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        activePointerId = event.getPointerId(newIndex)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                renderVisiblePages() // dispatches to background thread, never blocks the UI
                reportVisiblePage()
            }
        }
        return true
    }

    private fun currentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun clampMatrix() {
        var scale = currentScale()
        val clamped = scale.coerceIn(minScale.coerceAtMost(maxScale), maxScale)
        if (clamped != scale) {
            matrix.postScale(clamped / scale, clamped / scale, width / 2f, height / 2f)
            scale = clamped
        }

        matrix.getValues(matrixValues)
        val scaledW = contentWidthPx * scale
        val scaledH = contentHeightPx * scale

        val tx = if (scaledW <= width) (width - scaledW) / 2f
                 else matrixValues[Matrix.MTRANS_X].coerceIn(width - scaledW, 0f)
        val ty = if (scaledH <= height) (height - scaledH) / 2f
                 else matrixValues[Matrix.MTRANS_Y].coerceIn(height - scaledH, 0f)

        matrixValues[Matrix.MTRANS_X] = tx
        matrixValues[Matrix.MTRANS_Y] = ty
        matrix.setValues(matrixValues)
    }

    // --- Lazy, asynchronous render: only the visible page range (+ a small buffer) ---

    private fun visiblePageRange(): IntRange {
        if (pages.isEmpty()) return IntRange.EMPTY
        val scale = currentScale()
        matrix.getValues(matrixValues)
        val ty = matrixValues[Matrix.MTRANS_Y]
        val topY = -ty / scale
        val bottomY = (-ty + height) / scale

        var start = 0
        var end = pages.size - 1
        for (i in pages.indices) {
            if (pages[i].yOffset + pages[i].heightPx >= topY) { start = i; break }
        }
        for (i in pages.indices.reversed()) {
            if (pages[i].yOffset <= bottomY) { end = i; break }
        }
        return start..end
    }

    private fun renderVisiblePages() {
        if (pages.isEmpty()) return
        val visible = visiblePageRange()
        val bufferStart = (visible.first - 1).coerceAtLeast(0)
        val bufferEnd = (visible.last + 1).coerceAtMost(pages.size - 1)

        for (i in bufferStart..bufferEnd) {
            val alreadyHave = synchronized(bitmapCache) { bitmapCache.containsKey(i) }
            val alreadyQueued = synchronized(pendingRenders) { !pendingRenders.add(i) }
            if (!alreadyHave && !alreadyQueued) queueRenderPage(i)
        }

        val toEvict = synchronized(bitmapCache) {
            bitmapCache.keys.filter { it < bufferStart - 2 || it > bufferEnd + 2 }
        }
        for (i in toEvict) {
            val bmp = synchronized(bitmapCache) { bitmapCache.remove(i) }
            bmp?.recycle()
        }
    }

    private fun queueRenderPage(index: Int) {
        if (index !in pages.indices || released) return
        val meta = pages[index]
        val targetWidth = renderWidthPx
        val targetHeight = meta.heightPx.toInt().coerceAtLeast(1)

        val submitted = runCatching {
            renderExecutor.execute {
                if (released) {
                    synchronized(pendingRenders) { pendingRenders.remove(index) }
                    return@execute
                }
                val bmp = runCatching {
                    val r = renderer ?: return@runCatching null
                    synchronized(rendererLock) {
                        if (released) return@synchronized null
                        val page = r.openPage(index)
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bitmap
                    }
                }.getOrNull()

                mainHandler.post {
                    synchronized(pendingRenders) { pendingRenders.remove(index) }
                    if (released || bmp == null) {
                        bmp?.recycle()
                        return@post
                    }
                    synchronized(bitmapCache) {
                        if (bitmapCache.size >= maxCachedPages) {
                            val farthest = bitmapCache.keys.maxByOrNull { kotlin.math.abs(it - index) }
                            if (farthest != null) bitmapCache.remove(farthest)?.recycle()
                        }
                        bitmapCache[index] = bmp
                    }
                    invalidate()
                }
            }
        }.isSuccess

        // If the executor rejected the task (e.g. it was already shut down),
        // don't leave this page stuck marked as "pending" forever.
        if (!submitted) {
            synchronized(pendingRenders) { pendingRenders.remove(index) }
        }
    }

    private fun reportVisiblePage() {
        if (pages.isEmpty()) return
        val scale = currentScale()
        matrix.getValues(matrixValues)
        val ty = matrixValues[Matrix.MTRANS_Y]
        val centerY = (-ty + height / 2f) / scale
        var closest = 0
        for (i in pages.indices) {
            if (centerY <= pages[i].yOffset + pages[i].heightPx) { closest = i; break }
            closest = i
        }
        onPageIndicatorChanged?.invoke(closest + 1, pages.size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pages.isEmpty()) return
        canvas.save()
        canvas.concat(matrix)

        val visible = visiblePageRange()
        val drawStart = (visible.first - 1).coerceAtLeast(0)
        val drawEnd = (visible.last + 1).coerceAtMost(pages.size - 1)

        for (i in drawStart..drawEnd) {
            val meta = pages[i]
            val bmp = synchronized(bitmapCache) { bitmapCache[i] }
            if (bmp != null) {
                canvas.drawBitmap(bmp, 0f, meta.yOffset, pagePaint)
            } else {
                canvas.drawRect(0f, meta.yOffset, contentWidthPx, meta.yOffset + meta.heightPx, placeholderPaint)
            }
        }
        canvas.restore()
    }

    /** Recycles all cached page bitmaps and stops background rendering. Does NOT close the PdfRenderer — the caller owns that. */
    fun release() {
        released = true
        mainHandler.removeCallbacks(resizeSettleRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        renderExecutor.shutdownNow()
        runCatching { renderExecutor.awaitTermination(300, TimeUnit.MILLISECONDS) }
        synchronized(bitmapCache) {
            bitmapCache.values.forEach { it.recycle() }
            bitmapCache.clear()
        }
        synchronized(pendingRenders) { pendingRenders.clear() }
        pages.clear()
        renderer = null
        matrix.reset()
        initialized = false
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
