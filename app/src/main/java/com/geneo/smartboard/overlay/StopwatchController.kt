package com.geneo.smartboard.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Drives the floating stopwatch: start/pause, reset, and lap recording.
 * Ticks on the main looper using SystemClock.elapsedRealtime as the source of
 * truth so displayed time stays accurate even if the UI thread is briefly busy.
 */
class StopwatchController(private val root: View) {

    private val tvTime: TextView = root.findViewById(R.id.tvStopwatchTime)
    private val btnStartPause: ImageButton = root.findViewById(R.id.btnStartPauseStopwatch)
    private val btnReset: ImageButton = root.findViewById(R.id.btnResetStopwatch)
    private val btnLap: ImageButton = root.findViewById(R.id.btnLap)
    private val lapsContainer: LinearLayout = root.findViewById(R.id.lapsContainer)
    private val inflater = LayoutInflater.from(root.context)

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var baseElapsed = 0L // accumulated time before the current run segment
    private var runStartedAt = 0L
    private var lapCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 33L)
        }
    }

    init {
        updateDisplay()
        btnLap.alpha = 0.4f
        btnReset.alpha = 0.4f

        btnStartPause.setOnClickListener { toggleStartPause() }
        btnReset.setOnClickListener { reset() }
        btnLap.setOnClickListener { addLap() }
    }

    private fun toggleStartPause() {
        if (running) {
            // Pause
            baseElapsed += SystemClock.elapsedRealtime() - runStartedAt
            running = false
            handler.removeCallbacks(ticker)
            btnStartPause.setImageResource(R.drawable.ic_play)
            btnReset.alpha = 1f
        } else {
            // Start / resume
            runStartedAt = SystemClock.elapsedRealtime()
            running = true
            handler.post(ticker)
            btnStartPause.setImageResource(R.drawable.ic_pause)
            btnLap.alpha = 1f
            btnReset.alpha = 0.4f
        }
    }

    private fun reset() {
        if (running) return
        baseElapsed = 0L
        lapCount = 0
        lapsContainer.removeAllViews()
        btnLap.alpha = 0.4f
        btnReset.alpha = 0.4f
        updateDisplay()
    }

    private fun addLap() {
        if (!running) return
        lapCount += 1
        val row = inflater.inflate(R.layout.item_lap_row, lapsContainer, false)
        row.findViewById<TextView>(R.id.tvLapIndex).text = "Lap $lapCount"
        row.findViewById<TextView>(R.id.tvLapTime).text = format(currentElapsedMs())
        lapsContainer.addView(row, 0)
    }

    private fun currentElapsedMs(): Long {
        return if (running) baseElapsed + (SystemClock.elapsedRealtime() - runStartedAt) else baseElapsed
    }

    private fun updateDisplay() {
        tvTime.text = format(currentElapsedMs())
    }

    private fun format(ms: Long): String {
        val totalCentis = ms / 10
        val minutes = (totalCentis / 6000)
        val seconds = (totalCentis / 100) % 60
        val centis = totalCentis % 100
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centis)
    }

    /** Called when the tool window is closed, to stop the ticking loop. */
    fun stop() {
        handler.removeCallbacks(ticker)
    }
}
