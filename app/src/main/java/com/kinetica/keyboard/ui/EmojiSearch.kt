package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.AccentFolder

/**
 * Ranks the emoji for a typed query. Pure, so the ranking is pinned by tests on the real assets.
 *
 * Every query word must start some word of the emoji's, in any searched language. A whole word
 * beats a prefix (`lol` finds the laughing faces before the lollipop); within each, a word the
 * project wrote beats a CLDR name word, which beats a CLDR keyword.
 */
object EmojiSearch {

    /** Cells in the panel's row: three screens of eight, enough to scroll a little. */
    const val RESULTS = 24

    private val SPLIT = Regex("[\\s\\p{Punct}\\p{IsPunctuation}]+")

    private const val HAND = 3
    private const val NAME = 2
    private const val OTHER = 1

    /** Added to a whole-word match, so the weakest whole word still beats the strongest prefix. */
    private const val WHOLE = 3

    /** The query as folded words: lowercase, accents off, punctuation dropped. */
    fun words(query: String): List<String> =
        AccentFolder.fold(query.lowercase()).split(SPLIT).filter { it.isNotEmpty() }

    /**
     * [words] holds one list per searched language, each indexed by [EmojiCatalog.Entry.index].
     * Ties go to the emoji this user picks more ([uses]), then the shorter name, the more
     * generic emoji (`red heart` before `heart with arrow`), then Unicode order.
     */
    fun rank(
        query: String,
        entries: List<EmojiCatalog.Entry>,
        words: List<List<EmojiCatalog.Words>>,
        uses: Map<String, Int> = emptyMap(),
        limit: Int = RESULTS,
    ): List<EmojiCatalog.Entry> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        class Hit(val entry: EmojiCatalog.Entry, val score: Int, val nameSize: Int)
        val hits = ArrayList<Hit>()
        for (e in entries) {
            var best = 0
            var nameSize = Int.MAX_VALUE
            for (lang in words) {
                val w = lang.getOrNull(e.index) ?: continue
                val s = score(q, w)
                if (s > best || (s == best && s > 0 && w.name.size < nameSize)) {
                    best = s
                    nameSize = w.name.size
                }
            }
            if (best > 0) hits.add(Hit(e, best, nameSize))
        }
        hits.sortWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { uses[it.entry.ch] ?: 0 }
                .thenBy { it.nameSize }
                .thenBy { it.entry.index },
        )
        return hits.take(limit).map { it.entry }
    }

    /** Sum over the query words of each one's best match, or 0 when any word matches nothing. */
    private fun score(query: List<String>, w: EmojiCatalog.Words): Int {
        var total = 0
        for (q in query) {
            val s = maxOf(tier(q, w.hand, HAND), tier(q, w.name, NAME), tier(q, w.other, OTHER))
            if (s == 0) return 0
            total += s
        }
        return total
    }

    /** [field] plus [WHOLE] for a whole word, [field] for a word [q] only starts, 0 for none. */
    private fun tier(q: String, list: List<String>, field: Int): Int {
        var best = 0
        for (word in list) {
            if (word == q) return field + WHOLE
            if (word.startsWith(q)) best = field
        }
        return best
    }
}
