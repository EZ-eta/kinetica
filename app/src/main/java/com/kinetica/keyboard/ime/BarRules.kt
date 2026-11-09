package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.ui.BarMark

/**
 * Which dictionary each candidate came from, keyed the way `languageOf` looks a word up:
 * lowercased, so a capitalized German noun such as `Haus` is filed under German, not the
 * active language.
 */
internal fun provenanceOf(candidates: List<WordCandidate>): Map<String, String> {
    if (candidates.isEmpty()) return emptyMap()
    val out = HashMap<String, String>(candidates.size * 2)
    for (c in candidates) if (c.language.isNotEmpty()) out.putIfAbsent(c.word.lowercase(), c.language)
    return out
}

/**
 * Whether the word [written] in the editor is the [literal] its taps spell, up to folding: the
 * bar's literal zone then offers it as written, `grüße` for the taps `grusse`.
 */
internal fun writtenAsTyped(written: String, literal: String): Boolean =
    AccentFolder.fold(written.lowercase()) == literal

/**
 * Whether a commit puts the correction strip up. [offersCorrections] is the field's own
 * answer, which is where the privateMode / noLearning split is decided and documented.
 *
 * An [optionCount] of one is suppressed: its only zone is the selected one, and a tap on the
 * selected zone is a no-op, so the strip would offer nothing.
 */
internal fun showsCorrectionStrip(
    word: String,
    offersCorrections: Boolean,
    optionCount: Int,
): Boolean = word.isNotEmpty() && offersCorrections && optionCount > 1

/**
 * Whether the bar after a commit shows the strip's first [corrections] beside the next words
 * instead of the whole strip. Recent words on already give the bar to the next words, and with
 * none found (after a period) the strip stays, so a correction is never lost.
 */
internal fun showsBeside(
    stripUp: Boolean,
    nextWordOn: Boolean,
    besideOn: Boolean,
    recentWords: Boolean,
    corrections: Int,
    nextFound: Boolean,
): Boolean = stripUp && nextWordOn && besideOn && !recentWords && corrections > 0 && nextFound

/**
 * The typed letters' zone on the bar, or "" for none. Letters that leave the accents off a word
 * (`pojsc` for `pójść`) are not offered while autocorrect is on: the word is on the bar, and the
 * correction strip still holds the letters after a commit. With autocorrect off the user asked
 * for what they type.
 */
internal fun literalZone(literal: String, autocorrects: Boolean, leavesAccentsOff: Boolean, blocked: Boolean = false): String =
    if (blocked || (autocorrects && leavesAccentsOff)) "" else literal

/**
 * Composition-mode zone list: the ranked candidates, then the all-tap literal as the last zone
 * when it is not already among them, so an out-of-dictionary word commits verbatim with one
 * tap. Pure so the JVM suite can pin it; callers pass an empty literal for swipe buffers.
 */
internal fun suggestionZoneWords(candidates: List<String>, literal: String, literalSecond: Boolean = false): List<String> {
    if (literal.isEmpty()) return candidates
    if (!literalSecond) return if (candidates.contains(literal)) candidates else candidates + literal
    // Second, after the lead, so it is on the first page whatever the list holds.
    if (candidates.indexOf(literal) in 0..1) return candidates
    val rest = candidates - literal
    return rest.take(1) + literal + rest.drop(1)
}

/**
 * A bar word's mark: none for a bundled spelling ([inList], any resident list), `C` for one the
 * user learned or added ([personal]), else `+` when committing it learns it and `?` when it does
 * not (a field that keeps nothing, letters no word is made of, a blocked word).
 */
internal fun wordMark(inList: Boolean, personal: Boolean, learnable: Boolean, teachesNothing: Boolean, blocked: Boolean): BarMark =
    when {
        inList -> BarMark.NONE
        personal -> BarMark.PERSONAL
        learnable && !teachesNothing && !blocked -> BarMark.LEARN
        else -> BarMark.UNKNOWN
    }

/**
 * The bar word to draw bold: the one [spaceWord] matches, case aside (the commit capitalizes after
 * it), or -1 when space writes a word the bar does not show, or nothing.
 */
internal fun boldIndex(words: List<String>, spaceWord: String?): Int =
    if (spaceWord.isNullOrEmpty()) -1 else words.indexOfFirst { it.equals(spaceWord, ignoreCase = true) }

/**
 * The word a next-word prediction follows, from the text before the cursor, or null.
 *
 * Only after a finished word and its space: nothing mid-word, nothing after a sentence end or
 * other punctuation (there is no pair across it to read), and nothing inside a token such as
 * `name@mail.com`, whose last piece is not a word the user wrote.
 */
internal fun previousWordForPrediction(before: CharSequence?): String? {
    if (before.isNullOrEmpty() || before.last() != ' ') return null
    var end = before.length
    while (end > 0 && before[end - 1] == ' ') end--
    var start = end
    while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
    if (start == end) return null
    if (start > 0 && !before[start - 1].isWhitespace() && before[start - 1] !in PREDICT_OPENERS) return null
    val word = before.substring(start, end).trim('\'')
    return word.ifEmpty { null }
}

private const val PREDICT_OPENERS = "\"(«“‘"

/** Two languages' predictions in one list: strongest first, one entry per word. */
internal fun mergeNextWords(
    active: List<WordPredictor.NextWord>,
    other: List<WordPredictor.NextWord>,
    limit: Int,
): List<WordPredictor.NextWord> {
    val seen = HashSet<String>()
    return (active + other).sortedByDescending { it.score }.filter { seen.add(it.word.lowercase()) }.take(limit)
}
