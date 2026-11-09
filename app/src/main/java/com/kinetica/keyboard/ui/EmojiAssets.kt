package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Paint
import android.util.Log
import java.io.IOException

/**
 * The emoji set and its search words, read from assets once and shared by the pane and the
 * search panel. The set reaches Unicode 16 and ZWJ sequences, newer than many phones' fonts, so
 * what the font cannot draw as one glyph is left out here, once, not as a tofu box or as parts.
 *
 * Never throws: an empty pane is an inconvenience, a dead IME leaves the user unable to type.
 */
class EmojiAssets(private val context: Context) {

    private val glyphPaint = Paint()

    fun canDraw(ch: String): Boolean = glyphPaint.hasGlyph(ch)

    val catalog: EmojiCatalog by lazy {
        read("emoji/emoji.tsv") { EmojiCatalog.parse(it, ::canDraw) } ?: EmojiCatalog(emptyList())
    }

    private val words = HashMap<String, List<EmojiCatalog.Words>>()

    /** One language's words, loaded on its first search; a language without a file has none. */
    fun words(lang: String): List<EmojiCatalog.Words> =
        words.getOrPut(lang) { read("emoji/kw_$lang.tsv") { EmojiCatalog.parseWords(it) } ?: emptyList() }

    private fun <T> read(path: String, parse: (Sequence<String>) -> T): T? = try {
        context.assets.open(path).bufferedReader().useLines(parse)
    } catch (e: IOException) {
        Log.w(TAG, "emoji asset unreadable: $path", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "emoji asset unusable: $path", e)
        null
    }

    private companion object {
        const val TAG = "EmojiAssets"
    }
}
