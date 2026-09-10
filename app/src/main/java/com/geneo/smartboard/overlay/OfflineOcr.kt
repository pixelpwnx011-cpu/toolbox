package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Fully offline text recognition via Tesseract (Tesseract4Android) — no
 * Google Play Services, no network, nothing that depends on this board
 * actually having a working Play Store. Works especially well here since
 * our source images are clean, computer-rendered PDF text rather than a
 * messy camera photo, which is close to Tesseract's ideal input.
 *
 * Uses the bundled English "fast" trained-data model (~4MB,
 * assets/tessdata/eng.traineddata) — good accuracy/speed balance for
 * rendered text on a modest board CPU. The TessBaseAPI instance is
 * expensive to initialize (loads the model), so it's created once and
 * reused for every lookup, matching the same singleton pattern
 * OfflineDictionary uses for its database connection.
 */
object OfflineOcr {
    private const val LANGUAGE = "eng"

    @Volatile private var api: TessBaseAPI? = null

    @Synchronized
    private fun ensureReady(context: Context): TessBaseAPI? {
        api?.let { return it }
        return runCatching {
            val dataDir = File(context.filesDir, "tesseract")
            val tessdataDir = File(dataDir, "tessdata")
            if (!tessdataDir.exists()) tessdataDir.mkdirs()
            val target = File(tessdataDir, "$LANGUAGE.traineddata")
            if (!target.exists() || target.length() == 0L) {
                context.assets.open("tessdata/$LANGUAGE.traineddata").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val tess = TessBaseAPI()
            val initialized = tess.init(dataDir.absolutePath, LANGUAGE)
            if (!initialized) {
                tess.recycle()
                null
            } else {
                tess.also { api = it }
            }
        }.getOrElse {
            Log.w("OfflineOcr", "Failed to initialize offline OCR", it)
            null
        }
    }

    /** Fully offline text recognition. Returns null if Tesseract couldn't be readied (rare) or found no text. */
    fun recognize(context: Context, bitmap: Bitmap): String? {
        val tess = ensureReady(context) ?: return null
        return synchronized(tess) {
            runCatching {
                tess.setImage(bitmap)
                val text = tess.getUTF8Text()
                tess.clear() // drops the recognized-image state; keeps the loaded model ready for next time
                text?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }

    /** Releases Tesseract's native resources — call when the overlay service stops. */
    @Synchronized
    fun release() {
        runCatching { api?.recycle() }
        api = null
    }
}
