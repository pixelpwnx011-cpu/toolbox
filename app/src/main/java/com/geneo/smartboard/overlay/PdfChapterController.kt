package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView

/**
 * Opens a chapter PDF (offline, via Android's built-in PdfRenderer) and hands
 * it to a PdfContinuousView for zoomable/scrollable display. Owns the file
 * handle lifecycle; the view itself only owns the page bitmap cache.
 */
class PdfChapterController(root: View, context: Context, uri: Uri) {

    private val pdfView: PdfContinuousView = root.findViewById(R.id.pdfContinuousView)
    private val tvMessage: TextView = root.findViewById(R.id.tvPdfMessage)
    private val tvPageIndicator: TextView = root.findViewById(R.id.tvPageIndicator)
    private val seekBar: SeekBar = root.findViewById(R.id.pdfPageSeekBar)

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var userIsSeeking = false

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

    private fun showError(message: String) {
        pdfView.visibility = View.GONE
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = message
        seekBar.isEnabled = false
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
