package com.geneo.smartboard.overlay

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Scans a folder the user picked (via the system folder picker) for NCERT
 * Class 10 chapter PDFs, matching the standard layout:
 *   Science/ch<N>.pdf
 *   Math/ch<N>.pdf
 *   SSt/Eco|Geo|His|Pol Sc/ch<N>.pdf
 *   English/First flight|FWF/ch<N>.pdf
 *   Hindi/Kshitij|Kritika/ch<N>.pdf
 *
 * PDFs stay wherever the user put them on device storage — nothing is
 * copied or bundled into the app, so the app itself stays small. Folder/file
 * naming is matched case-insensitively with common variants; anything that
 * doesn't match a known name is skipped rather than guessed at.
 */
object BookImporter {

    private val CHAPTER_REGEX = Regex("""ch\s*0*(\d+)\.pdf$""", RegexOption.IGNORE_CASE)

    private val LEAF_FOLDER_MAP = mapOf(
        "eco" to "Economics",
        "economics" to "Economics",
        "geo" to "Geography",
        "geography" to "Geography",
        "his" to "History",
        "history" to "History",
        "pol sc" to "Political Science",
        "polsc" to "Political Science",
        "political science" to "Political Science",
        "first flight" to "English – First Flight",
        "firstflight" to "English – First Flight",
        "fwf" to "English – Footprints without Feet",
        "footprints" to "English – Footprints without Feet",
        "footprints without feet" to "English – Footprints without Feet",
        "kshitij" to "Hindi – Kshitij",
        "kritika" to "Hindi – Kritika"
    )

    data class ImportResult(val subjects: List<Subject>, val chapterCount: Int)

    fun scanFolder(context: Context, treeUri: Uri): ImportResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ImportResult(emptyList(), 0)

        val subjectsMap = LinkedHashMap<String, MutableList<Chapter>>()

        for (child in root.listFiles()) {
            if (!child.isDirectory) continue
            when (val name = child.name?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")) {
                "science" -> collectChapters(child, "Science", subjectsMap)
                "math", "maths", "mathematics" -> collectChapters(child, "Mathematics", subjectsMap)
                "sst", "social science" -> descendAndCollect(child, subjectsMap)
                "english" -> descendAndCollect(child, subjectsMap)
                "hindi" -> descendAndCollect(child, subjectsMap)
                else -> Unit
            }
        }

        val subjects = subjectsMap.map { (name, chapters) ->
            Subject(
                name,
                chapters.sortedBy { ch -> ch.title.filter { it.isDigit() }.toIntOrNull() ?: 0 }
                    .toMutableList()
            )
        }
        val totalChapters = subjects.sumOf { it.chapters.size }
        return ImportResult(subjects, totalChapters)
    }

    private fun descendAndCollect(parent: DocumentFile, into: MutableMap<String, MutableList<Chapter>>) {
        for (sub in parent.listFiles()) {
            if (!sub.isDirectory) continue
            val subName = sub.name?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: continue
            val subjectName = LEAF_FOLDER_MAP[subName] ?: continue
            collectChapters(sub, subjectName, into)
        }
    }

    private fun collectChapters(
        folder: DocumentFile,
        subjectName: String,
        into: MutableMap<String, MutableList<Chapter>>
    ) {
        val chapters = into.getOrPut(subjectName) { mutableListOf() }
        for (file in folder.listFiles()) {
            if (file.isDirectory) continue
            val fileName = file.name ?: continue
            val match = CHAPTER_REGEX.find(fileName) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            chapters.add(Chapter(title = "Chapter $number", pdfUri = file.uri.toString()))
        }
    }
}
