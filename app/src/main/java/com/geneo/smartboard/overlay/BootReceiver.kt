package com.geneo.smartboard.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // Only auto-start if the user completed setup once before (granted the
        // overlay permission and enabled the toolbox) — matches "runs automatically
        // on every boot after once setup".
        if (!Prefs.isOverlayEnabled(context)) return

        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
        if (!overlayGranted) return

        OverlayService.start(context)
    }
}
