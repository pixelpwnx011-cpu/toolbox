package com.geneo.smartboard.overlay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Turns a screenshot of a selected region into a word definition:
 * 1. OCR.space (free tier, requires the user's own API key — no OCR API is
 *    both free and keyless in a way that's safe to embed directly).
 * 2. dictionaryapi.dev for the actual definition (free, no key needed).
 *
 * Both are plain internet calls (no Google Play Services involved anywhere),
 * run on a background thread; the callback always fires on the main thread.
 */
object WordLookupHelper {

    data class Definition(val partOfSpeech: String, val meaning: String, val example: String?)
    data class LookupResult(val word: String, val phonetic: String?, val definitions: List<Definition>)

    private const val TIMEOUT_MS = 15000

    fun lookup(bitmap: Bitmap, apiKey: String, callback: (LookupResult?, String?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val outcome = runCatching {
                val recognizedText = ocrImage(bitmap, apiKey)
                    ?: throw IllegalStateException("Couldn't read any text in that selection")
                val word = recognizedText.trim()
                    .split(Regex("\\s+"))
                    .firstOrNull { it.any { c -> c.isLetter() } }
                    ?: throw IllegalStateException("No word found in that selection")
                fetchDefinition(word)
                    ?: throw IllegalStateException("No dictionary entry found for \"$word\"")
            }
            mainHandler.post {
                outcome.fold(
                    onSuccess = { callback(it, null) },
                    onFailure = { callback(null, it.message ?: "Lookup failed") }
                )
            }
        }.start()
    }

    private fun ocrImage(bitmap: Bitmap, apiKey: String): String? {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val body = "base64Image=" + URLEncoder.encode("data:image/png;base64,$base64", "UTF-8") +
            "&language=eng&scale=true&OCREngine=2"

        val conn = (URL("https://api.ocr.space/parse/image").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            if (json.optBoolean("IsErroredOnProcessing", false)) {
                return null
            }
            val results = json.optJSONArray("ParsedResults") ?: return null
            if (results.length() == 0) return null
            results.getJSONObject(0).optString("ParsedText", "").trim().takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchDefinition(word: String): LookupResult? {
        val cleanWord = word.trim().lowercase().filter { it.isLetter() }
        if (cleanWord.isEmpty()) return null

        val conn = (URL("https://api.dictionaryapi.dev/api/v2/entries/en/$cleanWord")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            if (conn.responseCode != 200) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(responseText)
            if (arr.length() == 0) return null
            val entry = arr.getJSONObject(0)

            val actualWord = entry.optString("word", cleanWord)
            val phonetic = entry.optString("phonetic", "").takeIf { it.isNotBlank() }

            val definitions = mutableListOf<Definition>()
            val meanings = entry.optJSONArray("meanings") ?: JSONArray()
            for (i in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(i)
                val partOfSpeech = meaning.optString("partOfSpeech", "")
                val defs = meaning.optJSONArray("definitions") ?: JSONArray()
                for (j in 0 until defs.length()) {
                    val def = defs.getJSONObject(j)
                    definitions.add(
                        Definition(
                            partOfSpeech = partOfSpeech,
                            meaning = def.optString("definition", ""),
                            example = def.optString("example", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            if (definitions.isEmpty()) return null
            LookupResult(actualWord, phonetic, definitions.take(6))
        } finally {
            conn.disconnect()
        }
    }
}
