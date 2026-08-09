package com.kinetica.keyboard.engine

import java.io.BufferedReader
import kotlin.math.max

/** One display spelling of a folded trie word, with its own quantized frequency. */
data class WordForm(val display: String, val freqByte: Int)

/**
 * A trie over folded (a-z + apostrophe) keys plus, for the folded keys whose
 * spelling differs from the key or that several spellings share ("po" vs
 * "pò", "senti" vs "sentì"), the display variants ordered by frequency.
 * English produces an empty map; Italian ~1k entries.
 */
class LoadedDictionary(
    val trie: Trie,
    val forms: Map<Int, List<WordForm>>,
)

/**
 * Parses the bundled text assets into the runtime structures. Runs once on a
 * background thread at IME startup (~300 ms for 47k words + 100k bigrams).
 */
object DictionaryLoader {

    /** Lines of "word&lt;TAB&gt;count". Invalid lines are skipped, not fatal. */
    fun load(
        reader: BufferedReader,
        extraWords: List<Pair<String, Int>> = emptyList(),
    ): LoadedDictionary {
        // Duplicate displays (corpus word also in the user dictionary) merge
        // by summing counts, so personal use adds to corpus evidence.
        val countByDisplay = LinkedHashMap<String, Int>(50_000 + extraWords.size)
        reader.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val count = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
            val word = line.substring(0, tab)
            countByDisplay[word] = (countByDisplay[word] ?: 0) + count
        }
        for ((word, count) in extraWords) {
            countByDisplay[word] = (countByDisplay[word] ?: 0) + count
        }

        val byFolded = LinkedHashMap<String, ArrayList<Pair<String, Int>>>(countByDisplay.size)
        for ((word, count) in countByDisplay) {
            byFolded.getOrPut(AccentFolder.fold(word)) { ArrayList(1) }.add(word to count)
        }

        // The trie node carries the strongest variant's count; weaker variants
        // keep their own quantized frequency in the forms table.
        var maxCount = 1L
        val trieInput = ArrayList<Pair<String, Int>>(byFolded.size)
        for ((folded, variants) in byFolded) {
            val top = variants.maxOf { it.second }
            trieInput.add(folded to top)
            maxCount = max(maxCount, top.toLong())
        }
        val trie = Trie.build(trieInput)

        val forms = HashMap<Int, List<WordForm>>()
        for ((folded, variants) in byFolded) {
            if (variants.size == 1 && variants[0].first == folded) continue
            val node = trie.nodeFor(folded)
            if (node == -1) continue    // dropped at build (length/charset)
            forms[node] = variants
                .sortedByDescending { it.second }
                .map { WordForm(it.first, Trie.freqByteFor(it.second, maxCount)) }
        }
        return LoadedDictionary(trie, forms)
    }

    /**
     * Raw per-user commit counts (word -> count) filtered by the personal
     * merge floor and scaled for [load]'s extraWords: rows below
     * PERSONAL_MERGE_MIN_COUNT (including de-reinforced-to-zero rows) never
     * reach the trie - see the constant's rationale.
     */
    fun userWordsForMerge(rows: List<Pair<String, Int>>): List<Pair<String, Int>> =
        rows.filter { it.second >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT }
            .map { it.first to it.second * KineticaConstants.USER_FREQ_SCALE }

    /** Legacy entry point for callers that only need the trie. */
    fun loadWordlist(
        reader: BufferedReader,
        extraWords: List<Pair<String, Int>> = emptyList(),
    ): Trie = load(reader, extraWords).trie

    /**
     * Lines of "w1&lt;TAB&gt;w2&lt;TAB&gt;count". Counts exceed Int range ("of the" in a
     * web corpus), hence Long. Pairs with endpoints missing from [trie] are
     * dropped: the table is keyed on trie node ids. Endpoints fold like
     * wordlist entries so accented Italian bigrams resolve.
     */
    fun loadBigrams(reader: BufferedReader, trie: Trie): BigramTable {
        val entries = ArrayList<Triple<Int, Int, Long>>(100_000)
        reader.forEachLine { line ->
            val t1 = line.indexOf('\t')
            if (t1 <= 0) return@forEachLine
            val t2 = line.indexOf('\t', t1 + 1)
            if (t2 <= t1 + 1) return@forEachLine
            val count = line.substring(t2 + 1).trim().toLongOrNull() ?: return@forEachLine
            val prevId = trie.nodeFor(AccentFolder.fold(line.substring(0, t1)))
            if (prevId == -1) return@forEachLine
            val nextId = trie.nodeFor(AccentFolder.fold(line.substring(t1 + 1, t2)))
            if (nextId == -1) return@forEachLine
            entries.add(Triple(prevId, nextId, count))
        }
        return BigramTable.build(entries)
    }
}
