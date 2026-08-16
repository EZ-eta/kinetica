package com.kinetica.keyboard.engine

import java.io.BufferedReader

/**
 * On-device port of `tools/generate_assets.py --merge-aosp`: merges an AOSP
 * LatinIME `wordlist.combined` file (HeliBoard/OpenBoard mirror format) into
 * the primary wordlist. Runs once at import time; the result is written to
 * app-internal storage and loaded instead of the bundled asset.
 */
object DictionaryMerger {

    // Mirror of the generator's per-language word shapes (WORD_RE in
    // tools/generate_assets.py). A language missing here silently filters its
    // accented words out of an AOSP import, so every registered language
    // needs its entry (ADDING_A_LANGUAGE.md §4); unknown codes fall back to
    // the plain ASCII shape rather than crash.
    private val WORD_RES = mapOf(
        "en" to Regex("^[a-z]+(?:'[a-z]+)*$"),
        "it" to Regex("^[a-zàèéìíîòóùú]+(?:'[a-zàèéìíîòóùú]+)*$"),
        "es" to Regex("^[a-záéíóúüñ]+(?:'[a-záéíóúüñ]+)*$"),
        "pl" to Regex("^[a-ząćęłńóśźż]+(?:'[a-ząćęłńóśźż]+)*$"),
    )

    /** The generator caps at 20 even though the engine trie accepts 24. */
    private const val MAX_WORD_LEN = 20

    private val AOSP_WORD = Regex("\\bword=([^,]+),f=(\\d+)")

    class MergeResult(
        /** Full merged list, sorted by count descending. */
        val rows: List<Pair<String, Int>>,
        /** How many words the AOSP list contributed beyond the primary. */
        val added: Int,
        /** How many parsable word lines the AOSP file contained. */
        val aospParsed: Int,
    )

    fun wordPattern(lang: String): Regex = WORD_RES[lang] ?: WORD_RES.getValue("en")

    /** Primary wordlist lines of "word&lt;TAB&gt;count"; invalid lines skipped. */
    fun readPrimary(reader: BufferedReader): List<Pair<String, Int>> {
        val rows = ArrayList<Pair<String, Int>>(50_000)
        reader.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val count = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
            rows.add(line.substring(0, tab) to count)
        }
        return rows
    }

    /**
     * Merges [aosp] (wordlist.combined lines) into [primary]. AOSP stores
     * log-quantized frequencies f in 0..255; they de-quantize onto the
     * primary raw-count scale via count = M^(f/255) (the inverse of the
     * quantizer, M = max primary count) so merged words rank sensibly
     * against OpenSubtitles counts. Abbreviations and possibly-offensive
     * entries are dropped: they would surface in suggestions with no way to
     * filter them later.
     */
    fun merge(primary: List<Pair<String, Int>>, aosp: BufferedReader, lang: String): MergeResult {
        val wordRe = wordPattern(lang)
        val primaryWords = HashSet<String>(primary.size * 2)
        var maxCount = 1
        for ((w, c) in primary) {
            primaryWords.add(w)
            if (c > maxCount) maxCount = c
        }

        val merged = ArrayList<Pair<String, Int>>()
        var parsed = 0
        aosp.forEachLine { raw ->
            val line = raw.trim()
            if (!line.startsWith("word=")) return@forEachLine
            if (line.contains("abbreviation") || line.contains("possibly_offensive=true")) {
                return@forEachLine
            }
            val m = AOSP_WORD.find(line) ?: return@forEachLine
            parsed++
            val word = m.groupValues[1].lowercase()
            val f = m.groupValues[2].toIntOrNull() ?: return@forEachLine
            if (word in primaryWords) return@forEachLine
            if (word.length > MAX_WORD_LEN || !wordRe.matches(word)) return@forEachLine
            val count = Math.max(
                1.0,
                Math.pow(maxCount.toDouble(), f.coerceIn(0, 255) / 255.0),
            ).toInt()
            merged.add(word to count)
            primaryWords.add(word)
        }
        val rows = (primary + merged).sortedByDescending { it.second }
        return MergeResult(rows, merged.size, parsed)
    }
}
