package com.kinetica.keyboard.ime

/** How far each side of the cursor [wordStepDirection] looks for a letter with a direction. */
internal const val WORD_STEP_LOOK_CHARS = 16

/**
 * The text-order direction of a word step the thumb made in [direction] (+1 right, -1 left).
 *
 * The spacebar's letter step sends DPAD keys, which the editor moves visually; the word step
 * sets the selection, which is text order. In right-to-left text those disagree, so the word step
 * is turned round when the nearest letter with a direction, on either side, is right-to-left.
 * Digits, spaces and punctuation have none and are looked past.
 */
internal fun wordStepDirection(direction: Int, before: CharSequence, after: CharSequence): Int {
    var i = before.length - 1
    var j = 0
    while (i >= 0 || j < after.length) {
        if (i >= 0) {
            rightToLeft(before[i])?.let { return if (it) -direction else direction }
            i--
        }
        if (j < after.length) {
            rightToLeft(after[j])?.let { return if (it) -direction else direction }
            j++
        }
    }
    return direction
}

private fun rightToLeft(c: Char): Boolean? = when (Character.getDirectionality(c)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
    else -> null
}

/**
 * The run of letters (and apostrophes) ending at [end] in [before], or "" when the
 * character there is not one.
 *
 * One walk for the word the cursor ends ([WordReopen.reloadWordUnderCursor]), the word an
 * automatic space would rejoin ([wordBeforeAutospace]) and the word a retype deletes
 * ([retypeSpan]), so they cannot disagree about where a word starts. The apostrophe counts:
 * `l'altro` and `don't` are one word to a reader.
 */
internal fun trailingLetterRun(before: CharSequence, end: Int = before.length): String {
    var start = end.coerceIn(0, before.length)
    val stop = start
    while (start > 0 && isWordChar(before[start - 1])) start--
    return before.subSequence(start, stop).toString()
}

/** What [trailingLetterRun] and [commitSpan] both count as belonging to a word. */
internal fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

/**
 * The line the cursor is on, or null when it cannot be seen whole.
 *
 * [before] and [after] are what the editor returned for a read of [read] characters each
 * way, and [selected] is any selection between them. A side with no newline that filled
 * its read may continue past it, so the line is refused, not cut. A selection that crosses a
 * line is not one line.
 */
internal fun lineAroundCursor(
    before: CharSequence,
    selected: CharSequence,
    after: CharSequence,
    read: Int,
): String? {
    if (selected.contains('\n')) return null
    val start = before.lastIndexOf('\n')
    if (start < 0 && before.length >= read) return null
    val end = after.indexOf('\n')
    if (end < 0 && after.length >= read) return null
    val head = before.substring(start + 1)
    val tail = if (end < 0) after.toString() else after.substring(0, end)
    return head + selected + tail
}

/** True when a word character sits on both sides of the cursor. */
internal fun cursorInsideWord(before: CharSequence, after: CharSequence): Boolean =
    before.isNotEmpty() && isWordChar(before[before.length - 1]) &&
        after.isNotEmpty() && isWordChar(after[0])

/**
 * The word the cursor is parked inside, split at the cursor, or null.
 *
 * Both halves are walked out of text read from the editor in the same call, so there is no
 * remembered length to drift from. The caller reads [maxLen] + 1
 * characters each side: a word that runs past either read is longer than [maxLen] and is
 * refused, not half re-cased.
 */
internal fun wordAroundCursor(before: CharSequence, after: CharSequence, maxLen: Int): WordAround? {
    val head = trailingLetterRun(before)
    var end = 0
    while (end < after.length && isWordChar(after[end])) end++
    val tail = after.subSequence(0, end).toString()
    if (head.isEmpty() || tail.isEmpty() || head.length + tail.length > maxLen) return null
    return WordAround(head, tail)
}

/** A word split at the cursor: [head] before it, [tail] after it. */
internal data class WordAround(val head: String, val tail: String)

/**
 * Whether the text before the cursor ends a sentence, so the next letter is capitalized.
 * [before] is at most `CAPS_LOOKBACK_CHARS` of the editor's text; empty is the start of the
 * field.
 *
 * `InputConnection.getCursorCapsMode` cannot answer this: `TextUtils.getCapsMode` reports
 * CAP_MODE_SENTENCES only once whitespace follows the terminator, and CAP_MODE_WORDS at a
 * paragraph start, which a CAP_SENTENCES field masks away. Those are the moments this
 * keyboard asks, since the space before a word is written with that word's commit.
 *
 * - A newline starts a paragraph and so a sentence.
 * - Closing punctuation is skipped, so 'he said "hi."' still ends one.
 * - A lone period inside its own word is an abbreviation, the platform's rule, so "e.g. "
 *   stays lower-case; a run of marks is a terminator, so "Wait..." is one.
 * - Accepted cost: "example.com" typed in a prose field reads "example.Com", as on stock
 *   keyboards; a URL field asks for no sentence caps.
 */
internal fun startsNewSentence(before: CharSequence): Boolean {
    var i = before.length
    while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
    val spaced = i < before.length
    if (i == 0) return true
    if (before[i - 1] == '\n') return true
    while (i > 0 && before[i - 1] in SENTENCE_CLOSERS) i--
    if (i == 0) return true
    val last = before[i - 1]
    if (last in SENTENCE_OPENERS) return true
    if (last !in SENTENCE_TERMINATORS) return false
    var run = i
    while (run > 0 && before[run - 1] in SENTENCE_TERMINATORS) run--
    if (i - run > 1 || last != '.') return true
    // One letter and a period with no space yet is an initial, the `e.` of `e.g.`;
    // once a space follows it may end a sentence, as `Plan A. ` does.
    if (!spaced && run >= 1 && before[run - 1].isLetter() &&
        (run == 1 || before[run - 2].isWhitespace())
    ) return false
    var j = run
    while (j > 0) {
        val c = before[j - 1]
        if (c == ' ' || c == '\t' || c == '\n') break
        if (c == '.') return false
        j--
    }
    return true
}

private const val SENTENCE_TERMINATORS = ".!?\u2026"

/** Skipped before looking for a terminator: quotes and closing brackets. */
internal const val SENTENCE_CLOSERS = ")]}\"'\u00bb\u201d\u2019"

/** Spanish opens a sentence with these, so the word after one begins it. */
internal const val SENTENCE_OPENERS = "\u00bf\u00a1"

// Punctuation that opens a word instead of joining one, so a space after the word that
// follows it is still right: brackets and the opening quote forms.
// Not the straight apostrophe, although it opens single quotes too: Italian elision is far
// more common than quoted text, and `al` is a word, so `l'altro` would become `l'al tro`.
// The cost is one space typed by hand inside 'quoted text'.
internal const val WORD_OPENERS = "([{\"\u00ab\u201c\u2018"

// Punctuation that binds two word-shaped pieces into one token. Every one of these
// appears mid-token in something a keyboard has to type without spacing it:
// snake_case, e-mail, don't, example.com, a/b, user@host, a\\b, C:name.
internal const val WORD_JOINERS = "_-'\u2019./\\@:"
