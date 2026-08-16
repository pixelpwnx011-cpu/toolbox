package com.geneo.smartboard.overlay

import android.content.Context

/**
 * Tiny SharedPreferences wrapper. Once the user enables the floating toolbox,
 * we remember that choice so [BootReceiver] can bring the overlay back after
 * every device reboot without any further setup.
 */
object Prefs {
    private const val PREFS_NAME = "geneo_overlay_prefs"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    fun isOverlayEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_ENABLED, false)
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_ENABLED, enabled)
            .apply()
    }
}
