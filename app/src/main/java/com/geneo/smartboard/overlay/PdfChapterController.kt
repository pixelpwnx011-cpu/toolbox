package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
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

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    init {
        pdfView.onPageIndicatorChanged = { current, total ->
            tvPageIndicator.text = "Page $current / $total"
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
            pdfView.attach(opened)
        } else {
            showError("Couldn't open this chapter. The file may have moved — try re-importing the folder.")
        }
    }

    private fun showError(message: String) {
        pdfView.visibility = View.GONE
        tvMessage.visibility = View.VISIBLE
        tvMessage.text = message
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
