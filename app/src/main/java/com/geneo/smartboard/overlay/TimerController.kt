package com.geneo.smartboard.overlay

import android.content.Context
import android.os.Build
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Drives the floating countdown timer. The +/-1m and +/-10s adjust buttons only
 * work while idle (not counting down) to keep the running state unambiguous;
 * they fade out and get disabled while a countdown is in progress.
 */
class TimerController(private val root: View) {

    private val context: Context = root.context
    private val tvTime: TextView = root.findViewById(R.id.tvTimerTime)
    private val btnStartPause: ImageButton = root.findViewById(R.id.btnStartPauseTimer)
    private val btnReset: ImageButton = root.findViewById(R.id.btnResetTimer)
    private val adjustRow: LinearLayout = root.findViewById(R.id.timerAdjustRow)

    private var totalSeconds = 5 * 60
    private var remainingMillis = totalSeconds * 1000L
    private var running = false
    private var countDownTimer: CountDownTimer? = null

    init {
        updateDisplay()

        root.findViewById<Button>(R.id.btnMinus1Min).setOnClickListener { adjust(-60) }
        root.findViewById<Button>(R.id.btnMinus10Sec).setOnClickListener { adjust(-10) }
        root.findViewById<Button>(R.id.btnPlus10Sec).setOnClickListener { adjust(10) }
        root.findViewById<Button>(R.id.btnPlus1Min).setOnClickListener { adjust(60) }

        btnStartPause.setOnClickListener { toggleStartPause() }
        btnReset.setOnClickListener { reset() }
    }

    private fun adjust(deltaSeconds: Int) {
        if (running) return
        totalSeconds = (totalSeconds + deltaSeconds).coerceIn(10, 99 * 60 + 59)
        remainingMillis = totalSeconds * 1000L
        updateDisplay()
    }

    private fun toggleStartPause() {
        if (running) {
            pause()
        } else {
            if (remainingMillis <= 0L) remainingMillis = totalSeconds * 1000L
            start()
        }
    }

    private fun start() {
        running = true
        setAdjustEnabled(false)
        btnStartPause.setImageResource(R.drawable.ic_pause)
        tvTime.setTextColor(resColor(R.color.geneo_text_primary))

        countDownTimer = object : CountDownTimer(remainingMillis, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                updateDisplay()
            }

            override fun onFinish() {
                remainingMillis = 0L
                running = false
                updateDisplay()
                btnStartPause.setImageResource(R.drawable.ic_play)
                setAdjustEnabled(true)
                onTimerFinished()
            }
        }.start()
    }

    private fun pause() {
        countDownTimer?.cancel()
        running = false
        btnStartPause.setImageResource(R.drawable.ic_play)
        setAdjustEnabled(true)
    }

    private fun reset() {
        countDownTimer?.cancel()
        running = false
        remainingMillis = totalSeconds * 1000L
        btnStartPause.setImageResource(R.drawable.ic_play)
        setAdjustEnabled(true)
        tvTime.setTextColor(resColor(R.color.geneo_text_primary))
        updateDisplay()
    }

    private fun setAdjustEnabled(enabled: Boolean) {
        adjustRow.alpha = if (enabled) 1f else 0.35f
        for (i in 0 until adjustRow.childCount) {
            adjustRow.getChildAt(i).isEnabled = enabled
        }
    }

    private fun onTimerFinished() {
        vibrate()
        flashComplete()
    }

    private fun vibrate() {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 250), -1)
        } else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            if (effect != null) vm?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (effect != null) {
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(600)
            }
        }
    }

    private fun flashComplete() {
        tvTime.text = "Time's up!"
        tvTime.setTextColor(resColor(R.color.geneo_danger))
        root.postDelayed({
            tvTime.setTextColor(resColor(R.color.geneo_text_primary))
            updateDisplay()
        }, 2500L)
    }

    private fun resColor(id: Int) = androidx.core.content.ContextCompat.getColor(context, id)

    private fun updateDisplay() {
        val totalSecs = (remainingMillis + 999) / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        tvTime.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    /** Called when the tool window is closed, to stop any pending countdown. */
    fun stop() {
        countDownTimer?.cancel()
    }
}
