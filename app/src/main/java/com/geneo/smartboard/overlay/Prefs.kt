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
    private const val KEY_OCR_API_KEY = "ocr_api_key"

    // Pre-configured so word lookup works without the user having to paste a
    // key in first. NOTE: if this project's repo is public on GitHub, this
    // key is visible to anyone who looks at the source — rotate it at
    // ocr.space if that's a concern.
    private const val DEFAULT_OCR_API_KEY = "K82434159888957"

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

    fun getOcrApiKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OCR_API_KEY, DEFAULT_OCR_API_KEY)
            ?.takeIf { it.isNotBlank() }
    }

    fun setOcrApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OCR_API_KEY, key.trim())
            .apply()
    }
}
