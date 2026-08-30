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
 * Turns a screenshot of a selected region into a word definition (plus a
 * Hindi translation):
 * 1. OCR.space (free tier, user-supplied API key) — reads the text.
 * 2. dictionaryapi.dev (free, no key) — English definition.
 * 3. MyMemory (free, no key, no Google account) — Hindi translation of the
 *    word and each definition.
 *
 * All plain internet calls, no Google Play Services involved anywhere; runs
 * on a background thread, callback always fires on the main thread.
 *
 * A dictionary defines individual words, not sentences — so when a whole
 * phrase/sentence is selected, this picks the most substantive word out of
 * it (skipping common short words like "the"/"is"/"a") and tries a few
 * candidates in order if the first choice has no dictionary entry, rather
 * than only ever trying the very first word OCR happened to read.
 */
object WordLookupHelper {

    data class Definition(
        val partOfSpeech: String,
        val meaning: String,
        val example: String?,
        val meaningHindi: String?
    )

    data class LookupResult(
        val word: String,
        val phonetic: String?,
        val wordHindi: String?,
        val definitions: List<Definition>,
        val recognizedText: String
    )

    private const val TIMEOUT_MS = 15000
    private const val MAX_OCR_DIMENSION = 1200 // keeps larger sentence-width selections within OCR.space's free-tier size limit

    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "am", "are", "was", "were", "be", "been", "being",
        "to", "of", "in", "on", "at", "it", "its", "and", "or", "but", "for", "with",
        "as", "that", "this", "these", "those", "he", "she", "they", "we", "you", "i",
        "his", "her", "their", "our", "your", "my", "him", "them", "us", "not", "so",
        "do", "does", "did", "has", "have", "had", "will", "would", "can", "could",
        "if", "then", "than", "there", "here", "up", "out", "no", "yes", "all"
    )

    fun lookup(bitmap: Bitmap, apiKey: String, callback: (LookupResult?, String?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val outcome = runCatching {
                val recognizedText = ocrImage(bitmap, apiKey)
                    ?: throw IllegalStateException("Couldn't read any text in that selection")

                val candidates = candidateWords(recognizedText)
                if (candidates.isEmpty()) {
                    throw IllegalStateException("No word found in that selection")
                }

                var raw: RawDefinition? = null
                for (candidate in candidates) {
                    raw = fetchDefinition(candidate)
                    if (raw != null) break
                }
                val found = raw ?: throw IllegalStateException(
                    "No dictionary entry found for the words in that selection"
                )

                // Translation is a nice-to-have — never let a translation
                // hiccup turn a successful lookup into a failure.
                val wordHindi = runCatching { translateToHindi(found.word) }.getOrNull()
                val definitionsWithHindi = found.definitions.map { def ->
                    def.copy(meaningHindi = runCatching { translateToHindi(def.meaning) }.getOrNull())
                }

                LookupResult(found.word, found.phonetic, wordHindi, definitionsWithHindi, recognizedText)
            }
            mainHandler.post {
                outcome.fold(
                    onSuccess = { callback(it, null) },
                    onFailure = { callback(null, it.message ?: "Lookup failed") }
                )
            }
        }.start()
    }

    /**
     * Cleans OCR output into a list of lookup candidates, most substantive
     * first: longest non-stopword tokens first, then shorter ones, then
     * stopwords as a last resort — so a whole sentence still finds a
     * meaningful word instead of only ever trying whatever came first.
     */
    private fun candidateWords(recognizedText: String): List<String> {
        val tokens = recognizedText
            .split(Regex("\\s+"))
            .map { it.filter { c -> c.isLetter() || c == '\'' }.trim('\'') }
            .filter { it.length > 1 }
            .distinctBy { it.lowercase() }

        val meaningful = tokens.filter { it.lowercase() !in STOPWORDS }
        val ordered = meaningful.sortedByDescending { it.length }
        val fallback = tokens.filter { it.lowercase() in STOPWORDS }
        return (ordered + fallback).distinctBy { it.lowercase() }.take(6)
    }

    private fun ocrImage(bitmap: Bitmap, apiKey: String): String? {
        val scaled = downscaleIfNeeded(bitmap)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        if (scaled !== bitmap) scaled.recycle()

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

    /** Keeps larger (e.g. whole-sentence) selections from exceeding OCR.space's free-tier request size limit. */
    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_OCR_DIMENSION) return bitmap
        val scale = MAX_OCR_DIMENSION / maxDim.toFloat()
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private data class RawDefinition(
        val word: String,
        val phonetic: String?,
        val definitions: List<Definition>
    )

    private fun fetchDefinition(word: String): RawDefinition? {
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
                            example = def.optString("example", "").takeIf { it.isNotBlank() },
                            meaningHindi = null
                        )
                    )
                }
            }
            if (definitions.isEmpty()) return null
            RawDefinition(actualWord, phonetic, definitions.take(6))
        } finally {
            conn.disconnect()
        }
    }

    /** Free, keyless translation via MyMemory — no Google account, no API key. */
    private fun translateToHindi(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val conn = (URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=en|hi")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            if (conn.responseCode != 200) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val translated = json.optJSONObject("responseData")?.optString("translatedText")
            translated?.takeIf { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
        } finally {
            conn.disconnect()
        }
    }
}
