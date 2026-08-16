package com.geneo.smartboard.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class BookLibraryActivity : AppCompatActivity() {

    private lateinit var tvImportStatus: TextView
    private lateinit var libraryListContainer: LinearLayout

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importFolder(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_library)

        tvImportStatus = findViewById(R.id.tvImportStatus)
        libraryListContainer = findViewById(R.id.libraryListContainer)

        findViewById<android.widget.Button>(R.id.btnImportFolder).setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        refreshList()
    }

    private fun importFolder(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val result = runCatching { BookImporter.scanFolder(this, treeUri) }.getOrNull()
        if (result == null || result.subjects.isEmpty()) {
            tvImportStatus.text = "Status: Couldn't find any chapters in that folder"
            tvImportStatus.setTextColor(ContextCompat.getColor(this, R.color.geneo_danger))
            Toast.makeText(
                this,
                "No matching Science/Math/SSt/English/Hindi folders found there",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        BookLibrary.save(this, result.subjects)
        tvImportStatus.text = "Status: Imported ${result.subjects.size} subjects, ${result.chapterCount} chapters"
        tvImportStatus.setTextColor(ContextCompat.getColor(this, R.color.geneo_success))
        Toast.makeText(this, "Library updated", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun refreshList() {
        libraryListContainer.removeAllViews()
        val subjects = BookLibrary.load(this)
        val inflater = LayoutInflater.from(this)

        if (subjects.all { it.chapters.isEmpty() }) {
            val empty = TextView(this)
            empty.text = "Nothing imported yet."
            empty.setTextColor(ContextCompat.getColor(this, R.color.geneo_text_secondary))
            empty.textSize = 13f
            libraryListContainer.addView(empty)
            return
        }

        for (subject in subjects) {
            if (subject.chapters.isEmpty()) continue
            val row = inflater.inflate(R.layout.item_subject_summary, libraryListContainer, false)
            row.findViewById<TextView>(R.id.tvSubjectName).text = subject.name
            row.findViewById<TextView>(R.id.tvChapterCount).text =
                "${subject.chapters.size} chapter${if (subject.chapters.size == 1) "" else "s"}"
            libraryListContainer.addView(row)
        }
    }
}
