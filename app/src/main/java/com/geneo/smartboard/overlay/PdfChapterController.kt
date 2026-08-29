package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView

/**
 * Opens a chapter PDF (offline, via Android's built-in PdfRenderer) and hands
 * it to a PdfContinuousView for zoomable/scrollable display. Owns the file
 * handle lifecycle; the view itself only owns the page bitmap cache.
 *
 * [onRequestMeaningLookup] fires with a screenshot of whatever region the
 * user drag-selected on the page, for the caller (OverlayService, which owns
 * the WindowManager) to show a word-meaning popup for.
 */
class PdfChapterController(
    root: View,
    context: Context,
    uri: Uri,
    private val onRequestMeaningLookup: (Bitmap) -> Unit
) {

    private val pdfView: PdfContinuousView = root.findViewById(R.id.pdfContinuousView)
    private val tvMessage: TextView = root.findViewById(R.id.tvPdfMessage)
    private val tvPageIndicator: TextView = root.findViewById(R.id.tvPageIndicator)
    private val seekBar: SeekBar = root.findViewById(R.id.pdfPageSeekBar)
    private val selectionOverlay: SelectionOverlayView = root.findViewById(R.id.pdfSelectionOverlay)
    private val btnSelectText: ImageButton = root.findViewById(R.id.btnSelectText)

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var userIsSeeking = false
    private var selectModeActive = false

    init {
        pdfView.onPageIndicatorChanged = { current, total ->
            tvPageIndicator.text = "$current / $total"
            // Don't fight the user's own drag on the slider with programmatic updates.
            if (!userIsSeeking) {
                seekBar.max = (total - 1).coerceAtLeast(0)
                seekBar.progress = (current - 1).coerceAtLeast(0)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) pdfView.scrollToPage(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { userIsSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) { userIsSeeking = false }
        })

        btnSelectText.setOnClickListener { toggleSelectMode() }
        selectionOverlay.onSelectionComplete = { rect ->
            runCatching { captureAndLookup(rect) }
            setSelectMode(false) // one-shot: exit select mode after each selection
        }

        val opened = runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            pfd = descriptor
            descriptor?.let { PdfRenderer(it) }
        }.getOrElse {
            Log.w("PdfChapterController", "Failed to open chapter PDF", it)
            null
        }
        renderer = opened

        if (opened != null && opened.pageCount > 0) {
            pdfView.visibility = View.VISIBLE
            tvMessage.visibility = View.GONE
            seekBar.isEnabled = true
            pdfView.attach(opened)
        } else {
            showError("Couldn't open this chapter. The file may have moved — try re-importing the folder.")
        }
    }

    private fun toggleSelectMode() = setSelectMode(!selectModeActive)

    private fun setSelectMode(active: Boolean) {
        selectModeActive = active
        selectionOverlay.visibility = if (active) View.VISIBLE else View.GONE
        btnSelectText.alpha = if (active) 1f else 0.7f
        btnSelectText.setBackgroundResource(
            if (active) R.drawable.bg_calc_equals else R.drawable.bg_round_button
        )
    }

    /** Screenshots the currently-rendered page area, cropped to the selected region, for lookup. */
    private fun captureAndLookup(rect: android.graphics.RectF) {
        val w = pdfView.width
        val h = pdfView.height
        if (w <= 0 || h <= 0) return

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        pdfView.draw(canvas)

        val left = rect.left.toInt().coerceIn(0, w - 1)
        val top = rect.top.toInt().coerceIn(0, h - 1)
        val right = rect.right.toInt().coerceIn(left + 1, w)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, h)

        val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        full.recycle()
        onRequestMeaningLookup(cropped)
    }

    private fun showError(message: String) {
        pdfView.visibility = View.GONE
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = message
        seekBar.isEnabled = false
        btnSelectText.isEnabled = false
        btnSelectText.alpha = 0.4f
    }

    /** Releases native PDF resources — must be called when the tool window closes. */
    fun close() {
        runCatching { pdfView.release() }
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null
        pfd = null
    }
}
