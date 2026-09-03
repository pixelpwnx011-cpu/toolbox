package com.geneo.smartboard.overlay

import android.content.Context
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
 * Hindi translation).
 *
 * Speed/reliability design (this used to be very slow and time out a lot):
 * 1. The DEFINITION step checks the bundled offline dictionary (~108,000
 *    words, instant, no network) first — see OfflineDictionary. Only words
 *    missing from that dataset fall back to Merriam-Webster online, and
 *    only a handful of candidates are tried (not every word OCR read).
 * 2. The callback fires TWICE on success: immediately once OCR + the
 *    definition are ready (no waiting on translation), then again if/when
 *    the Hindi translation arrives shortly after. The English definition is
 *    never held up waiting on translation calls.
 * 3. Only the word itself and its single best definition are translated —
 *    not every definition — and the two translation calls run in parallel,
 *    not one after another.
 * 4. Timeouts are tuned per call type instead of one generous 15s for
 *    everything, so a genuinely stuck call fails fast instead of eating
 *    15 seconds before anything else even starts.
 *
 * OCR.space (free tier, user-supplied key) and MyMemory (free, keyless) are
 * still real network calls — no Google Play Services involved anywhere.
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

    private const val OCR_TIMEOUT_MS = 10000
    private const val TEXT_TIMEOUT_MS = 6000
    private const val MAX_OCR_DIMENSION = 1200
    private const val MAX_ONLINE_CANDIDATES = 3 // offline dictionary can afford to check more; the network fallback shouldn't

    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "am", "are", "was", "were", "be", "been", "being",
        "to", "of", "in", "on", "at", "it", "its", "and", "or", "but", "for", "with",
        "as", "that", "this", "these", "those", "he", "she", "they", "we", "you", "i",
        "his", "her", "their", "our", "your", "my", "him", "them", "us", "not", "so",
        "do", "does", "did", "has", "have", "had", "will", "would", "can", "could",
        "if", "then", "than", "there", "here", "up", "out", "no", "yes", "all"
    )

    fun lookup(
        context: Context,
        bitmap: Bitmap,
        ocrApiKey: String,
        dictionaryApiKey: String?,
        callback: (LookupResult?, String?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val appContext = context.applicationContext

        Thread {
            val outcome = runCatching {
                val recognizedText = try {
                    ocrImage(bitmap, ocrApiKey) ?: throw IllegalStateException("Couldn't read any text in that selection")
                } finally {
                    bitmap.recycle()
                }

                val candidates = candidateWords(recognizedText)
                if (candidates.isEmpty()) {
                    throw IllegalStateException("No word found in that selection")
                }

                // 1. Offline first — instant, no network, works for ~108,000 words.
                var word: String? = null
                var offlineDefs: List<OfflineDictionary.OfflineDefinition>? = null
                for (candidate in candidates) {
                    val hit = OfflineDictionary.lookup(appContext, candidate)
                    if (hit != null) {
                        word = candidate
                        offlineDefs = hit
                        break
                    }
                }

                var result: LookupResult
                if (offlineDefs != null && word != null) {
                    result = LookupResult(
                        word = word,
                        phonetic = null,
                        wordHindi = null,
                        definitions = offlineDefs.map { Definition(it.partOfSpeech, it.meaning, it.example, null) },
                        recognizedText = recognizedText
                    )
                } else if (dictionaryApiKey != null) {
                    // 2. Fall back to Merriam-Webster for words the offline set doesn't have.
                    var raw: RawDefinition? = null
                    for (candidate in candidates.take(MAX_ONLINE_CANDIDATES)) {
                        raw = fetchDefinitionMW(candidate, dictionaryApiKey)
                        if (raw != null) break
                    }
                    val found = raw ?: throw IllegalStateException(
                        "No dictionary entry found for the words in that selection"
                    )
                    result = LookupResult(found.word, found.phonetic, null, found.definitions, recognizedText)
                } else {
                    throw IllegalStateException(
                        "No dictionary entry found offline, and no Merriam-Webster key set for online lookup"
                    )
                }
                result
            }

            outcome.fold(
                onSuccess = { result ->
                    // Show the English definition immediately — don't make the
                    // user wait on translation for it.
                    mainHandler.post { callback(result, null) }
                    translateAndUpdate(result) { updated ->
                        mainHandler.post { callback(updated, null) }
                    }
                },
                onFailure = { error ->
                    mainHandler.post { callback(null, error.message ?: "Lookup failed") }
                }
            )
        }.start()
    }

    /** Translates just the word and its first definition, in parallel, then reports the merged result once. */
    private fun translateAndUpdate(result: LookupResult, onUpdated: (LookupResult) -> Unit) {
        var wordHindi: String? = null
        var firstDefHindi: String? = null

        val t1 = Thread { wordHindi = runCatching { translateToHindi(result.word) }.getOrNull() }
        val t2 = Thread {
            val firstMeaning = result.definitions.firstOrNull()?.meaning
            if (firstMeaning != null) {
                firstDefHindi = runCatching { translateToHindi(firstMeaning) }.getOrNull()
            }
        }
        t1.start(); t2.start()
        runCatching { t1.join(TEXT_TIMEOUT_MS.toLong() + 500) }
        runCatching { t2.join(TEXT_TIMEOUT_MS.toLong() + 500) }

        if (wordHindi == null && firstDefHindi == null) return // nothing to add, skip the update

        val updatedDefinitions = result.definitions.mapIndexed { index, def ->
            if (index == 0 && firstDefHindi != null) def.copy(meaningHindi = firstDefHindi) else def
        }
        onUpdated(result.copy(wordHindi = wordHindi ?: result.wordHindi, definitions = updatedDefinitions))
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
            connectTimeout = OCR_TIMEOUT_MS
            readTimeout = OCR_TIMEOUT_MS
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            if (json.optBoolean("IsErroredOnProcessing", false)) return null
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

    private data class RawDefinition(val word: String, val phonetic: String?, val definitions: List<Definition>)

    private fun fetchDefinitionMW(word: String, apiKey: String): RawDefinition? {
        val cleanWord = word.trim().lowercase().filter { it.isLetter() }
        if (cleanWord.isEmpty()) return null
        val encoded = URLEncoder.encode(cleanWord, "UTF-8")

        val conn = (URL("https://www.dictionaryapi.com/api/v3/references/collegiate/json/$encoded?key=$apiKey")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TEXT_TIMEOUT_MS
            readTimeout = TEXT_TIMEOUT_MS
        }
        return try {
            if (conn.responseCode != 200) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(responseText)
            if (arr.length() == 0 || arr.opt(0) !is JSONObject) return null // string array = "not found, here are suggestions"

            var displayWord: String? = null
            var phonetic: String? = null
            val definitions = mutableListOf<Definition>()

            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val meta = entry.optJSONObject("meta") ?: continue
                val entryId = meta.optString("id")
                if (!entryId.substringBefore(":").equals(cleanWord, ignoreCase = true)) continue

                if (displayWord == null) {
                    displayWord = meta.optJSONArray("stems")?.optString(0)
                        ?: entry.optJSONObject("hwi")?.optString("hw")?.replace("*", "")
                        ?: cleanWord
                }
                if (phonetic == null) {
                    phonetic = entry.optJSONObject("hwi")
                        ?.optJSONArray("prs")?.optJSONObject(0)?.optString("mw")
                        ?.takeIf { it.isNotBlank() }
                }

                val partOfSpeech = entry.optString("fl", "")
                val shortDefs = entry.optJSONArray("shortdef") ?: JSONArray()
                val example = runCatching { findFirstExample(entry.optJSONArray("def")) }.getOrNull()
                for (j in 0 until shortDefs.length()) {
                    val defText = cleanMwMarkup(shortDefs.getString(j))
                    if (defText.isBlank()) continue
                    definitions.add(
                        Definition(
                            partOfSpeech = partOfSpeech,
                            meaning = defText,
                            example = if (j == 0) example else null,
                            meaningHindi = null
                        )
                    )
                }
            }
            if (definitions.isEmpty()) return null
            RawDefinition(displayWord ?: cleanWord, phonetic, definitions.take(6))
        } finally {
            conn.disconnect()
        }
    }

    /** Recursively finds MW's first "vis" (verbal illustration/example) anywhere in the nested def structure. */
    private fun findFirstExample(node: Any?): String? {
        when (node) {
            is JSONArray -> {
                if (node.length() == 2 && node.opt(0) == "vis") {
                    val visArr = node.opt(1) as? JSONArray
                    val text = visArr?.optJSONObject(0)?.optString("t")
                    if (!text.isNullOrBlank()) return cleanMwMarkup(text)
                }
                for (i in 0 until node.length()) {
                    findFirstExample(node.opt(i))?.let { return it }
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    findFirstExample(node.opt(keys.next()))?.let { return it }
                }
            }
        }
        return null
    }

    /** Strips Merriam-Webster's markup tokens (e.g. {bc}, {it}...{/it}) down to plain text. */
    private fun cleanMwMarkup(text: String): String {
        var t = text
        t = t.replace("{bc}", "")
        t = Regex("\\{it\\}(.*?)\\{/it\\}").replace(t) { it.groupValues[1] }
        t = Regex("\\{b\\}(.*?)\\{/b\\}").replace(t) { it.groupValues[1] }
        t = Regex("\\{wi\\}(.*?)\\{/wi\\}").replace(t) { it.groupValues[1] }
        t = Regex("\\{sx\\|([^|}]*)\\|[^}]*\\}").replace(t) { it.groupValues[1] }
        t = Regex("\\{a_link\\|([^}]*)\\}").replace(t) { it.groupValues[1] }
        t = Regex("\\{d_link\\|([^|}]*)\\|[^}]*\\}").replace(t) { it.groupValues[1] }
        t = t.replace("{ldquo}", "\u201C").replace("{rdquo}", "\u201D")
        t = Regex("\\{[^{}]*\\}").replace(t, "")
        return t.trim()
    }

    /** Free, keyless translation via MyMemory — no Google account, no API key. */
    private fun translateToHindi(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val conn = (URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=en|hi")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TEXT_TIMEOUT_MS
            readTimeout = TEXT_TIMEOUT_MS
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
