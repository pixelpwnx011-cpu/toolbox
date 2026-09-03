package com.geneo.smartboard.overlay

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * A real, bundled offline English dictionary — ~108,000 words / ~163,000
 * definitions, built from the open-licensed (CC BY-SA 4.0, derived from
 * Princeton WordNet) wordset-dictionary project, stripped down to just
 * word/part-of-speech/definition/example and stored as a 16.5MB SQLite
 * database asset.
 *
 * This is what actually fixes "new words are slow": for the large majority
 * of common English vocabulary, the definition itself needs zero network
 * calls at all — no OCR-then-dictionary-then-translation chain, just an
 * indexed SQLite query that returns in under a millisecond. Only words
 * missing from this dataset (rare/technical/proper nouns) fall back to the
 * online Merriam-Webster lookup.
 *
 * The database ships in assets/ (can't be queried directly from inside the
 * compressed APK) and is copied to app-private storage once, on first use.
 */
object OfflineDictionary {
    private const val ASSET_NAME = "dictionary.db"
    private const val DB_FILE_NAME = "offline_dictionary.db"

    @Volatile private var db: SQLiteDatabase? = null

    data class OfflineDefinition(val partOfSpeech: String, val meaning: String, val example: String?)

    @Synchronized
    private fun ensureOpen(context: Context): SQLiteDatabase? {
        db?.let { return it }
        return runCatching {
            val dbFile = File(context.filesDir, DB_FILE_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) {
                context.assets.open(ASSET_NAME).use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { db = it }
        }.getOrElse {
            Log.w("OfflineDictionary", "Failed to open bundled dictionary", it)
            null
        }
    }

    /** Instant, fully offline lookup. Returns null if the word isn't in the bundled dataset. */
    fun lookup(context: Context, word: String): List<OfflineDefinition>? {
        val cleanWord = word.trim().lowercase().filter { it.isLetter() }
        if (cleanWord.isEmpty()) return null
        val database = ensureOpen(context) ?: return null

        return runCatching {
            val results = mutableListOf<OfflineDefinition>()
            database.rawQuery(
                "SELECT part_of_speech, definition, example FROM definitions WHERE word = ? LIMIT 6",
                arrayOf(cleanWord)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(
                        OfflineDefinition(
                            partOfSpeech = cursor.getString(0) ?: "",
                            meaning = cursor.getString(1) ?: "",
                            example = cursor.getString(2)
                        )
                    )
                }
            }
            results.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
