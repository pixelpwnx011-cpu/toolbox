package com.geneo.smartboard.overlay

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Self-healing safety net for boards where BOOT_COMPLETED is unreliable —
 * some heavily customized board firmwares (locked-down launchers, no real
 * Settings app, no visible "Autostart" control) suppress or delay boot
 * broadcasts to third-party apps in ways no in-app permission can fix.
 *
 * WorkManager's own periodic-work scheduling is handled by the OS's
 * JobScheduler and is rescheduled by WorkManager's own bundled receiver
 * after every reboot automatically — independent of, and often more
 * resistant to OEM restrictions than, a plain BroadcastReceiver listening
 * for BOOT_COMPLETED directly. Every 15 minutes (the platform's minimum
 * interval for periodic work) this checks whether the overlay should be
 * running and restarts it if it isn't, so even a boot-time failure heals
 * itself shortly after rather than staying down all day.
 */
class OverlayWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!Prefs.isOverlayEnabled(context)) return Result.success()

        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
        if (!overlayGranted) return Result.success()

        if (!OverlayService.isRunning) {
            OverlayService.start(context)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "overlay_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OverlayWatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
