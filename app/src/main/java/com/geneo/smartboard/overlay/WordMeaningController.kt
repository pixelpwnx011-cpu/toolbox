package com.geneo.smartboard.overlay

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Populates the word-meaning popup's content — word, phonetic, Hindi
 * translation, and each definition (with its own Hindi translation and
 * example sentence) grouped by part of speech. No audio/pronunciation
 * button by design.
 */
class WordMeaningController(root: View) {

    private val context: Context = root.context
    private val container: LinearLayout = root.findViewById(R.id.meaningContentContainer)

    fun showLoading() {
        container.removeAllViews()
        addText("Looking up…", 14f, color(R.color.geneo_text_secondary))
    }

    fun showError(message: String) {
        container.removeAllViews()
        addText(message, 14f, color(R.color.geneo_danger))
    }

    fun showResult(result: WordLookupHelper.LookupResult) {
        container.removeAllViews()

        addText(result.word, 24f, color(R.color.geneo_text_primary), bold = true, topMargin = 0)
        result.phonetic?.let {
            addText(it, 14f, color(R.color.geneo_text_secondary), topMargin = 2)
        }
        result.wordHindi?.let {
            addText(it, 15f, color(R.color.geneo_accent), topMargin = 2)
        }

        // If a whole phrase/sentence was selected, show what was actually
        // read so it's clear which word out of it is being defined below.
        val wordCount = result.recognizedText.trim().split(Regex("\\s+")).size
        if (wordCount > 1) {
            addText("Selected: \u201C${result.recognizedText.trim()}\u201D", 12f, color(R.color.geneo_text_secondary), italic = true, topMargin = 8)
        }

        val grouped = LinkedHashMap<String, MutableList<WordLookupHelper.Definition>>()
        for (def in result.definitions) {
            grouped.getOrPut(def.partOfSpeech.ifBlank { "meaning" }) { mutableListOf() }.add(def)
        }

        var number = 1
        for ((partOfSpeech, defs) in grouped) {
            addText(partOfSpeech, 13f, color(R.color.geneo_accent), italic = true, topMargin = 18)
            for (def in defs) {
                addText("$number. ${def.meaning}", 15f, color(R.color.geneo_text_primary), topMargin = 10)
                def.meaningHindi?.let {
                    addText(it, 13.5f, color(R.color.geneo_text_secondary), topMargin = 2)
                }
                def.example?.let { addExampleBubble(it) }
                number++
            }
        }
    }

    private fun addText(
        text: String,
        sizeSp: Float,
        textColor: Int,
        bold: Boolean = false,
        italic: Boolean = false,
        topMargin: Int = 8
    ) {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = sizeSp
        tv.setTextColor(textColor)
        tv.typeface = when {
            bold && italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(topMargin)
        tv.layoutParams = params
        container.addView(tv)
    }

    private fun addExampleBubble(example: String) {
        val tv = TextView(context)
        tv.text = "\u201C$example\u201D"
        tv.textSize = 13.5f
        tv.setTextColor(color(R.color.geneo_text_secondary))
        tv.setBackgroundResource(R.drawable.bg_calc_digit)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(6)
        tv.layoutParams = params
        container.addView(tv)
    }

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
