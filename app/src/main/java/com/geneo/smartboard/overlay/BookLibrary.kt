package com.geneo.smartboard.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Chapter(
    var title: String,
    var pdfUri: String? = null
)

data class Subject(
    var name: String,
    val chapters: MutableList<Chapter> = mutableListOf()
)

/**
 * Persists the NCERT Class 10 book library (subjects -> chapters -> a
 * user-picked PDF file for each) as JSON in SharedPreferences. Seeded with
 * the standard NCERT Class 10 subjects but no chapters/PDFs — those are
 * added by the school/teacher from the app (Manage NCERT Library screen),
 * since NCERT revises chapter lists periodically and the actual PDF has to
 * come from a file the school has, not a hardcoded link.
 */
object BookLibrary {
    private const val PREFS_NAME = "geneo_overlay_prefs"
    private const val KEY_LIBRARY = "ncert_class10_library_v1"

    private fun defaultSubjects(): MutableList<Subject> = mutableListOf(
        Subject("Science"),
        Subject("Mathematics"),
        Subject("Social Science"),
        Subject("English"),
        Subject("Hindi")
    )

    fun load(context: Context): MutableList<Subject> {
        val raw = prefs(context).getString(KEY_LIBRARY, null) ?: return defaultSubjects()
        return runCatching { decode(raw) }.getOrElse { defaultSubjects() }
    }

    fun save(context: Context, subjects: List<Subject>) {
        prefs(context).edit().putString(KEY_LIBRARY, encode(subjects)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encode(subjects: List<Subject>): String {
        val arr = JSONArray()
        for (subject in subjects) {
            val subjObj = JSONObject()
            subjObj.put("name", subject.name)
            val chapArr = JSONArray()
            for (chapter in subject.chapters) {
                val chapObj = JSONObject()
                chapObj.put("title", chapter.title)
                chapObj.put("pdfUri", chapter.pdfUri ?: JSONObject.NULL)
                chapArr.put(chapObj)
            }
            subjObj.put("chapters", chapArr)
            arr.put(subjObj)
        }
        return arr.toString()
    }

    private fun decode(raw: String): MutableList<Subject> {
        val arr = JSONArray(raw)
        val result = mutableListOf<Subject>()
        for (i in 0 until arr.length()) {
            val subjObj = arr.getJSONObject(i)
            val chapters = mutableListOf<Chapter>()
            val chapArr = subjObj.optJSONArray("chapters") ?: JSONArray()
            for (j in 0 until chapArr.length()) {
                val chapObj = chapArr.getJSONObject(j)
                chapters.add(
                    Chapter(
                        title = chapObj.optString("title", "Untitled chapter"),
                        pdfUri = if (chapObj.isNull("pdfUri")) null else chapObj.optString("pdfUri")
                    )
                )
            }
            result.add(Subject(name = subjObj.optString("name", "Subject"), chapters = chapters))
        }
        return if (result.isEmpty()) defaultSubjects() else result
    }
}
