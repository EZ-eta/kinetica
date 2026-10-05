package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.WordPredictor

/**
 * The last few committed words and the candidates each one beat, for the recent-words bar.
 * A word is acted on only where the editor still holds it: [alignRecent] finds
 * each one again in the text before the cursor, never by a remembered offset.
 */
class RecentWords(private val max: Int) {

    /** A commit as written, and the candidates it beat, best first, never itself. */
    class Entry(val word: String, val alternatives: List<String>, val languages: Map<String, String>)

    private val entries = ArrayDeque<Entry>()

    /** Oldest first. */
    fun entries(): List<Entry> = entries.toList()

    fun onCommit(word: String, alternatives: List<String>, languages: Map<String, String>) {
        entries.addLast(Entry(word, distinctOthers(word, alternatives), languages))
        while (entries.size > max) entries.removeFirst()
    }

    /**
     * The word [back] commits before the newest (0 is the newest) now reads [word]. The word it
     * replaced leads the alternatives, so one more tap puts it back.
     */
    fun onReplaced(back: Int, word: String) {
        val i = entries.size - 1 - back
        if (i !in entries.indices) return
        val e = entries[i]
        entries[i] = Entry(word, distinctOthers(word, listOf(e.word) + e.alternatives), e.languages)
    }

    /** The language [word] was decoded in, newest entry first, or null when no entry names it. */
    fun languageOf(word: String): String? {
        val w = word.lowercase()
        for (e in entries.reversed()) e.languages[w]?.let { return it }
        return null
    }

    /** The newest commit was taken back out of the editor, by a retype. */
    fun dropNewest() {
        if (entries.isNotEmpty()) entries.removeLast()
    }

    fun clear() = entries.clear()

    private fun distinctOthers(word: String, words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        for (w in words) {
            if (w.equals(word, ignoreCase = true) || out.any { it.equals(w, ignoreCase = true) }) continue
            out.add(w)
        }
        return out
    }
}

/**
 * Where each of [words] (oldest first) sits in [before], newest first, as the distance from
 * the word's first letter to the cursor. The last [pending] characters are the word being
 * typed and are skipped. Words may be separated by at most [maxGap] characters that are not
 * letters. The walk stops at the first word the text does not hold where expected, so only the
 * run the editor still agrees with is offered.
 */
internal fun alignRecent(before: CharSequence, pending: Int, words: List<String>, maxGap: Int = RECENT_MAX_GAP): List<Int> {
    val out = ArrayList<Int>(words.size)
    var i = before.length - pending
    if (i < 0) return out
    for (w in words.asReversed()) {
        if (w.isEmpty()) break
        var gap = 0
        while (i > 0 && gap < maxGap && !isRecentWordChar(before[i - 1])) {
            i--
            gap++
        }
        // A word flush against what follows it is part of a longer run.
        if (gap == 0 && i < before.length) break
        val start = i - w.length
        if (start < 0) break
        if (!before.subSequence(start, i).toString().equals(w, ignoreCase = true)) break
        if (start > 0 && isRecentWordChar(before[start - 1])) break
        out.add(before.length - start)
        i = start
    }
    return out
}

/**
 * What replaces the last [span] characters of [before] when the word starting there, [oldLength]
 * long, becomes [replacement]: the replacement and everything after the old word, unchanged.
 */
internal fun recentRewrite(before: CharSequence, span: Int, oldLength: Int, replacement: String): String {
    val start = before.length - span
    return replacement + before.subSequence(start + oldLength, before.length)
}

/**
 * [alternatives] in [word]'s own language, by [languages] (word to language, lowercase keys)
 * with [default] for a word it does not name; all of them when none share it. Another
 * language's reading of the same gesture is rarely the swap anyone wants.
 */
internal fun sameLanguage(
    alternatives: List<String>,
    languages: Map<String, String>,
    word: String,
    default: String,
): List<String> {
    fun lang(w: String) = languages[w.lowercase()] ?: default
    val own = lang(word)
    val kept = alternatives.filter { lang(it) == own }
    return kept.ifEmpty { alternatives }
}

/** One learned pair moved by a swap: (previous, word, change). */
internal data class PairMove(val prev: String, val word: String, val delta: Int)

/**
 * The language a swap's pair move is filed under: a pair taken back was learned with the old word,
 * in its language; a pair added belongs to the new word's.
 */
internal fun pairMoveLanguage(move: PairMove, oldLang: String, newLang: String): String =
    if (move.delta < 0) oldLang else newLang

/** One stored spelling of a learned pair and its count. */
internal data class StoredPair(val prev: String, val next: String, val count: Int)

/**
 * How much to take from each stored spelling of the pair [key] to take back [amount], largest
 * first. The live count adds every spelling up under the folded key, so a decrement aimed at one
 * spelling could miss the row that holds the count.
 */
internal fun pickPairRows(rows: List<StoredPair>, key: String, amount: Int): List<Pair<StoredPair, Int>> {
    var left = amount
    val out = ArrayList<Pair<StoredPair, Int>>()
    for (r in rows.filter { it.count > 0 && WordPredictor.pairKey(it.prev, it.next) == key }.sortedByDescending { it.count }) {
        if (left <= 0) break
        val take = minOf(left, r.count)
        out.add(r to take)
        left -= take
    }
    return out
}

/**
 * What a recent-word swap does to the learned pairs around it: the pairs with [old] give their
 * count to the same pairs with [new], on each side that has a neighbour. [prev] is the word
 * before, [next] the committed word after, either null when there is none to read.
 */
internal fun recentPairMoves(prev: String?, old: String, new: String, next: String?): List<PairMove> {
    if (old.equals(new, ignoreCase = true)) return emptyList()
    val out = ArrayList<PairMove>(4)
    if (prev != null) {
        out.add(PairMove(prev, old, -1))
        out.add(PairMove(prev, new, 1))
    }
    if (next != null) {
        out.add(PairMove(old, next, -1))
        out.add(PairMove(new, next, 1))
    }
    return out
}

/** [word] cased like [written]: all capitals, a leading capital, or as it is. */
internal fun matchCase(written: CharSequence, word: String): String = when {
    written.length > 1 && written.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
    written.isNotEmpty() && written[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
    else -> word
}

private fun isRecentWordChar(c: Char): Boolean = c.isLetter() || c == '\''

/** Separators allowed between two recent words: a space, punctuation, a quote. */
private const val RECENT_MAX_GAP = 4
