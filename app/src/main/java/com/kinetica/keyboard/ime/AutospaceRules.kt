package com.kinetica.keyboard.ime

import com.kinetica.keyboard.settings.Prefs

/**
 * Whether picking a word from the bar writes a space after it.
 *
 * Not in a one-token field (an address, a URL, an expansion trigger): the same reason
 * [autospacesTappedWord] arms nothing there. A space in such a field is never wanted; a trigger
 * field strips it on save (#19).
 */
internal fun spacesAfterPick(addressField: Boolean): Boolean = !addressField

/**
 * True when a word typed entirely by tapping has earned an automatic space.
 *
 * A swipe ends with a lift, so its word is over; a tapped letter looks like the middle of a
 * longer word. The gates:
 * - [literalIsWord] is necessary and far from sufficient: of tap states spelling a real word,
 *   57% were mid-word. The delay filters most (the median gap inside a word is 168 ms), and
 *   about one fire in five is still premature at 300 ms, barely fewer at 800 ms. Rejected: a
 *   frequency floor removes good fires as fast as bad, and "top candidate equals the literal"
 *   removes 9% of misfires for 6% of good fires; the OpenSubtitles lists hold `ke`, `wh` and
 *   `whe`. The error is affordable because
 *   [retractsAutospace] takes the space back when another letter of the word follows.
 * - [literalIsStandaloneLetter] covers one letter: one-letter words were 12% of prose words
 *   (16 of 130), and every letter a-z is in every bundled list, so [StandaloneLetters] holds
 *   a curated set. One letter waits longer ([singleLetterDelayMs]); at 300 ms it spaces 17 of
 *   22 one-letter words with no premature fire on a finished word.
 * - [carriesNoToken]: a reload after a deleted space re-seeds the word as taps, and without
 *   this the timer put the space back. Scoped to one decode, since a flag that outlives its
 *   word silenced correct spaces.
 * - [addressField]: no automatic space in an email or URL field.
 *
 * All gates are read here so the whole decision stays in one testable place.
 */
internal fun autospacesTappedWord(
    enabled: Boolean,
    hasSwipeToken: Boolean,
    literal: String,
    literalIsWord: Boolean,
    literalIsStandaloneLetter: Boolean,
    addressField: Boolean,
    joinedToWhatPrecedes: Boolean,
    joinedTokenIsWord: Boolean,
    joinedByApostrophe: Boolean,
    carriesNoToken: Boolean,
): Boolean = enabled && !hasSwipeToken && !addressField && !carriesNoToken &&
    // A word that continues an earlier token takes no space unless the whole token is a
    // word: `don't` is, `automaticop` is not. An English contraction autospaces this way,
    // since its tail is too short for the length rule (see joinedTokenForAutospace).
    //
    // After an apostrophe the piece is judged on its own when the token is not a word:
    // Italian elisions (`dell'anno`, `d'accordo`, `un'ora`) are in no wordlist, while `anno`
    // and `accordo` are. Only the apostrophe: `log-12.com`, `example.com`, `name@mail.com`
    // and `notes_14.log` must keep refusing.
    //
    // The cost: `'quoted text'` gains a space, and a pause inside an elision can split it,
    // since `l'al` and `dell'ann` are entries. That was chosen over failing
    // elisions; retractsAutospace takes the space back at the next letter.
    if (joinedToWhatPrecedes) {
        joinedTokenIsWord || (joinedByApostrophe && literal.length >= 2 && literalIsWord)
    } else if (literal.length == 1) {
        // One letter: the only question is whether it is a word in this language (see
        // StandaloneLetters). After the joined branch, so the `a` of `dell'anno`, preceded
        // by an apostrophe, never reaches here.
        literalIsStandaloneLetter
    } else {
        literal.length >= 2 && literalIsWord
    }

/**
 * True when the character joining this word to what precedes it is an apostrophe.
 *
 * Separate from [joinsPrecedingToken], which asks whether the token is finished here, not
 * which joiner it is. Only the apostrophe lets [autospacesTappedWord] judge the piece after
 * it, because elision is a word boundary the dictionary cannot see. Both quote forms count:
 * the apostrophe key offers the typographic one as an alternate.
 *
 * [before] is the text preceding the word, so its last character is the one in question.
 */
internal fun joinedByApostrophe(before: CharSequence): Boolean =
    before.isNotEmpty() && (before[before.length - 1] == '\'' || before[before.length - 1] == '\u2019')

/**
 * The letters after the last apostrophe in [word], or "" when there is none.
 *
 * The retraction's second question: `dell'ann` + `o` must retract, but the elided form is
 * not in the trie, so the tail `anno` is asked. Separate from [wordBeforeAutospace], which
 * must return the whole run, or `it's` + `a` would fuse into `it'sa`.
 */
internal fun tailAfterLastApostrophe(word: String): String {
    val i = maxOf(word.lastIndexOf('\''), word.lastIndexOf('\u2019'))
    return if (i < 0) "" else word.substring(i + 1)
}

/**
 * True when a word containing at least one swipe token has earned an automatic space.
 *
 * One predicate for both call sites, so neither can miss a gate. A swipe earns its space
 * because the finger lifted and the decode landed; only two conditions remain.
 *
 * - No "user deleted a space" gate: a word swiped after a slide is a new word and earns its
 *   space, and such a flag outlives its word.
 * - [carriesNoToken] is a belt: `WordComposer.seed` replaces the token list with taps, so a
 *   reloaded word carries no swipe today. It keeps a reload that ever seeds a gesture right.
 * - [addressField]: no automatic space in an email or URL field.
 */
internal fun autospacesSwipedWord(
    hasSwipeToken: Boolean,
    addressField: Boolean,
    carriesNoToken: Boolean,
): Boolean = hasSwipeToken && !addressField && !carriesNoToken

/**
 * The whole editor token the word being typed belongs to, letters and joiners together, or ""
 * when there is nothing word-shaped there.
 *
 * A tapped apostrophe is not a letter to the composer: it routes to `onPunctuation`, which
 * finalizes the word, so `don't` reaches the composer as `don` and a one-letter `t`. Read back
 * from the editor, `don't`, `it's` and `can't` are words and space, while `log-12.com` and
 * `example.com` are not (`Alphabet.encode` rejects digits) and stay refused.
 *
 * Italian elisions are absent from the wordlist (its ten apostrophe entries are junk such as
 * `e'o` and `n'roll`), so [autospacesTappedWord] judges the piece after the apostrophe for
 * them. Generated elided forms would still let one be decoded as a single gesture.
 *
 * [before] is the text before the cursor with the word's own letters still on the end.
 */
internal fun joinedTokenForAutospace(before: CharSequence): String {
    var start = before.length
    while (start > 0) {
        val c = before[start - 1]
        // Digits are part of the token, so `log-12.com` is one thing, and they make the
        // lookup fail, since Alphabet.encode refuses them.
        if (c.isLetterOrDigit() || c in WORD_JOINERS) start-- else break
    }
    val token = before.subSequence(start, before.length).toString()
    // A run with no letter in it spells nothing to look up.
    return if (token.any { it.isLetter() }) token else ""
}

/**
 * True when the character before the word being typed joins it to what precedes, so the
 * word may be a fragment of a longer token.
 *
 * This only says the token is not finished here; whether it is finished at all is
 * [joinedTokenForAutospace]'s question, and [autospacesTappedWord] asks both. Decided from
 * the text, not the field type, so `notes_14.log` in a rename box, `e-mail`, `don't`,
 * `example.com` and `a/b` all refuse without knowing the editor.
 *
 * [before] is the text preceding the word, so its last character is the one in question.
 * Whitespace and opening punctuation allow the space, so `"hello world"` still spaces; an
 * empty read is the start of the field and allows it too.
 */
internal fun joinsPrecedingToken(before: CharSequence): Boolean {
    if (before.isEmpty()) return false
    val c = before[before.length - 1]
    if (c.isWhitespace()) return false
    if (c in SENTENCE_OPENERS || c in WORD_OPENERS) return false
    return c.isLetterOrDigit() || c in WORD_JOINERS
}

/**
 * True when an automatic space should be taken back because the word was not over.
 *
 * Only a space this keyboard put after a tapped word, and only while nothing else has
 * happened since. A swipe's autospace is never retracted: the gesture ended, so a following
 * letter starts a new word.
 *
 * [elapsedMs] is bounded because a space is provisional only while typing is in flow: `is`,
 * a break, then `land` must not produce `island`.
 *
 * [fusedIsPrefix] retracts only when the fused form could still become a word: `autom` can,
 * `automaticop` cannot. Over 67 captured word pairs it refuses 70% of would-be fusions, and
 * no first word of six letters or more fuses, the class that produced empty decodes. Short
 * words stay ambiguous, which the window covers.
 *
 * [swipeStartedFirst] refuses when the other thumb's swipe landed before the letter and is still
 * down: the swipe is the new word's first token, and a word a swipe starts never retracts. Over
 * 33 captured retractions it refuses one, `I` fused into `noticed`.
 */
internal fun retractsAutospace(
    fromTappedWord: Boolean,
    tentativeLength: Int,
    elapsedMs: Long,
    windowMs: Long,
    fusedIsPrefix: Boolean,
    swipeStartedFirst: Boolean = false,
): Boolean = retractRefusal(fromTappedWord, tentativeLength, elapsedMs, windowMs, fusedIsPrefix, swipeStartedFirst) == null

/**
 * Why [retractsAutospace] keeps the space, or null when it takes it back. `space` and `word` are
 * the preconditions: no tapped word's space, or a word already underway. Then the first gate that
 * refuses: `late` (the window), `prefix` (the dictionary), `shift` (no learned camel-case word,
 * asked instead of the dictionary when [shifted]), `held` (the other thumb's swipe). A capture
 * names the gate, which the window alone could not tell from the dictionary.
 */
internal fun retractRefusal(
    fromTappedWord: Boolean,
    tentativeLength: Int,
    elapsedMs: Long,
    windowMs: Long,
    fusedIsPrefix: Boolean,
    swipeStartedFirst: Boolean = false,
    shifted: Boolean = false,
): String? = when {
    !fromTappedWord -> "space"
    tentativeLength != 0 -> "word"
    elapsedMs > windowMs -> "late"
    !fusedIsPrefix -> if (shifted) "shift" else "prefix"
    swipeStartedFirst -> "held"
    else -> null
}

/**
 * Whether a learned spelling continues [base] with [capital]: `Fake` + `C` against
 * `FakeCompanyName`. After a shift press this replaces the dictionary's question: `isl` is a live
 * prefix, yet `is`, shift, `Land` is two words. [capital] is the letter as typed, so a press that
 * leaves it lowercase rejoins nothing. The cost: a camel-case word never typed splits the first time.
 */
internal fun learnedCamelPrefix(base: String, capital: Char, displays: Iterable<String>): Boolean {
    if (base.isEmpty() || !capital.isUpperCase()) return false
    return displays.any {
        it.length > base.length && it.regionMatches(0, base, 0, base.length, ignoreCase = true) &&
            it[base.length] == capital
    }
}

/**
 * The window a shift press is read against: the user's, never under [floorMs]. A press is part of
 * the next letter, so it gets at least as long as a letter does.
 */
internal fun shiftRetractMs(windowMs: Long, floorMs: Long = Prefs.SHIFT_RETRACT_MIN_MS.toLong()): Long =
    maxOf(windowMs, floorMs)

/**
 * What [retractsAutospace] reads as the time since an automatic space written at [autospaceAt],
 * for a letter at [now]: the clock stops at [shiftAt], a shift press after the space (-1 for
 * none), because a camel-case capital spends the window on the press. The cost: shift at once,
 * a long pause, then a letter still retracts when [learnedCamelPrefix] agrees.
 */
internal fun retractClockMs(now: Long, autospaceAt: Long, shiftAt: Long): Long =
    if (shiftAt >= 0) maxOf(0L, shiftAt - autospaceAt) else now - autospaceAt

/**
 * The word an automatic space would be taken back into: the letter run right before the space
 * at the cursor, or "" when there is no such space or word, and the retraction refuses.
 *
 * Split out from [Autospace.retract] so the string half can be tested; the dictionary half is
 * one [WordPredictor.isLivePrefix] call at the site.
 */
internal fun wordBeforeAutospace(before: CharSequence): String {
    if (before.isEmpty() || before[before.length - 1] != ' ') return ""
    return trailingLetterRun(before, before.length - 1)
}

/**
 * How long a one-letter word waits before its automatic space arrives.
 *
 * Longer than a word's, because one letter is weaker evidence: `a` is a word and also the
 * start of `and` and `arrivato`, and only silence tells them apart. Over 22 captured
 * one-letter words against 28 word-starts whose first letter is also a word:
 *
 * | delay | spaced | premature |
 * |---|---|---|
 * | 204 | 21/22 | 10/28 |
 * | 250 | 19/22 | 5/28 |
 * | 275 | 18/22 | 3/28 |
 * | 300 | 17/22 | 3/28 |
 * | 350 | 17/22 | 3/28 |
 * | 600 | 14/22 | 1/28 |
 *
 * [Prefs.SINGLE_LETTER_MIN_DELAY_MS] sits mid-plateau at 300: 275 is 1 ms above a cost sample,
 * 300 has 26 ms of margin below and 161 above. A floor, not a fixed value: a single letter
 * should never space sooner than a word whose delay the user raised.
 */
internal fun singleLetterDelayMs(tapDelayMs: Long, floorMs: Long): Long =
    maxOf(tapDelayMs, floorMs)

/**
 * How long an undecodable buffer stays open: twice the swipe delay, never under
 * [STALE_TIMEOUT_FLOOR_MS].
 *
 * The swipe delay goes down to 10 ms (#2), and twice that would close a word typed in pieces
 * 20 ms after each piece. The floor is twice the old 100 ms minimum, so every setting
 * reachable before keeps its timeout.
 */
internal fun staleTimeoutMs(swipeDelayMs: Long): Long = maxOf(2 * swipeDelayMs, STALE_TIMEOUT_FLOOR_MS)

internal const val STALE_TIMEOUT_FLOOR_MS = 200L

/**
 * How long the autospace timer still waits: [delay] counted from [liftAt], the last thumb's lift,
 * not from [now], when the decode landed. Counted from the decode, a 10 ms setting would wait
 * tens of milliseconds longer than it says. Never negative; a lift time unknown (-1) or in the future
 * waits the whole delay.
 */
internal fun autospaceWait(delay: Long, liftAt: Long, now: Long): Long {
    if (liftAt < 0 || liftAt > now) return delay
    return (delay - (now - liftAt)).coerceIn(0L, delay)
}

/**
 * Whether [text] is punctuation that sits against the word before it, so an automatic space
 * in front of it should go.
 *
 * Sentence and clause punctuation and closing brackets hug: "Hi" + "!" is "Hi!". An opening
 * bracket, a dash, a digit or a letter keep the space, as in "a (b)". Listed explicitly, not
 * derived from a character class: an em dash is punctuation and takes a space, and an
 * apostrophe hugs but never arrives here.
 */
internal fun hugsPreviousWord(text: String, hugging: String = Prefs.DEFAULT_NO_SPACE_BEFORE): Boolean =
    text.length == 1 && text[0] in hugging

/**
 * How much one backspace removes with tidy spaces on: a run of spaces before the cursor
 * becomes one, so the next press takes the last; anything else is one character.
 */
internal fun backspaceSpan(before: CharSequence): Int {
    var n = 0
    while (n < before.length && before[before.length - 1 - n] == ' ') n++
    return if (n >= 2) n - 1 else 1
}

/** What a space tap does with tidy spaces on. */
internal enum class SpaceTap { WRITE, SWALLOW, SENTENCE_END }

/**
 * A space tapped straight after a space: with the double-space full stop on and a word before
 * it, the pair ends the sentence as a double tap would; otherwise the tap is dropped, because
 * the space is already there. [before] is the two characters before the cursor.
 */
internal fun tidySpaceTap(before: CharSequence, doubleSpacePeriod: Boolean): SpaceTap = when {
    before.isEmpty() || before[before.length - 1] != ' ' -> SpaceTap.WRITE
    doubleSpacePeriod && doubleSpaceEndsSentence(before) -> SpaceTap.SENTENCE_END
    else -> SpaceTap.SWALLOW
}

/**
 * Whether the automatic space after a word should be written: with tidy spaces on, not when the
 * editor already has whitespace, a closing quote or bracket, or a mark of [hugging] right after
 * the cursor, so a word written in front of `:` or `!` gains no space before it.
 */
internal fun autospaceWanted(
    tidy: Boolean,
    after: CharSequence?,
    hugging: String = Prefs.DEFAULT_NO_SPACE_BEFORE,
): Boolean {
    if (!tidy || after.isNullOrEmpty()) return true
    val c = after[0]
    if (c in SENTENCE_CLOSERS || c in hugging) return false
    // A space or tab holds only with a word right after it (`word| next`). A newline, or a
    // space a field keeps at its end, is not the separator the next word needs: counting them
    // silenced every autospace in such a field (17 of 17 holds in one capture).
    if (c == ' ' || c == '\t') return !(after.length >= 2 && !after[1].isWhitespace())
    return true
}

/**
 * Whether the space just typed can become a sentence end.
 *
 * [before] is the two characters before the cursor. True only for a single space after a
 * letter or digit, the one shape where ". " reads as finishing a sentence. A run of spaces is
 * deliberate whitespace, and after punctuation `e.g. ` would become `e.g.. `.
 */
internal fun doubleSpaceEndsSentence(before: CharSequence): Boolean {
    if (before.length < 2) return false
    if (before[before.length - 1] != ' ') return false
    return before[before.length - 2].isLetterOrDigit()
}
