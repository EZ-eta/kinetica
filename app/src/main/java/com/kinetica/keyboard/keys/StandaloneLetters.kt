package com.kinetica.keyboard.keys

/**
 * Which single letters are words on their own, per language.
 *
 * A curated list rather than a dictionary test, and the dictionary is exactly why. **Every
 * letter a-z is an entry in every bundled wordlist**, with frequencies large enough to look
 * deliberate: `en` holds `l` at 126 518, `s` at 110 199, `t` at 72 881, `d` at 64 304. They
 * are OpenSubtitles artefacts - split contractions, initials, list markers - so
 * `WordPredictor.isWord` answers yes for a lone `t` and no dictionary rule can separate
 * that from a lone `a`.
 *
 * Kept beside [AutoCapitalization] because it is the same kind of fact and takes the same
 * argument: the LANGUAGE decides, not the position. `e` is a word in Italian and not in
 * English; `y` is one in Spanish and not anywhere else here.
 *
 * [lang] is the ACTIVE language code, matching [AutoCapitalization.forWord]. The known cost
 * of that choice: with per-word auto-detect on, typing Italian while English is active gets
 * the English set, so an Italian `e` will not space. Widening to a union of the enabled
 * languages was rejected for the same reason the capitalization rule rejects it - it would
 * import each language's risk into the others, and a wrong fire costs a space the user has
 * to delete.
 *
 * Why this list is safe to spend a premature space on is [autospacesTappedWord]'s business,
 * and the answer is the delay: one letter is weaker evidence than a word, so it waits
 * longer. KNOWN_ISSUES item 48 carries the sweep.
 */
object StandaloneLetters {

    /**
     * True when [letter] is a word by itself in [lang]. Case- and accent-insensitive by
     * construction: the composer's literal is already folded to a-z, so Italian `è`
     * arrives here as `e`.
     */
    fun isWord(letter: Char, lang: String): Boolean =
        letter.lowercaseChar() in setFor(lang)

    /**
     * The letters, and each set is a closed list of function words rather than a judgement
     * about frequency:
     *
     *  - `en` - the article `a` and the pronoun `I`. `AutoCapitalization` already turns a
     *    lone `i` into `I`, so this makes the pronoun space and capitalize together.
     *  - `it` - `a` (to), `e` (and), `i` (the, masculine plural), `o` (or). `è` (is) folds
     *    onto `e`.
     *  - `es` - `a` (to), `e` and `y` (and), `o` (or).
     *  - `pl` - `a`, `i` (and), `o` (about), `u` (at), `w` (in), `z` (with). The largest
     *    set and the only one with no capture behind it: `w` and `z` are also common word
     *    starts in Polish, so this is the language most likely to want a longer delay.
     *    Unmeasured, and said so.
     */
    private fun setFor(lang: String): Set<Char> = when (lang) {
        "it" -> IT
        "es" -> ES
        "pl" -> PL
        else -> EN
    }

    private val EN = setOf('a', 'i')
    private val IT = setOf('a', 'e', 'i', 'o')
    private val ES = setOf('a', 'e', 'o', 'y')
    private val PL = setOf('a', 'i', 'o', 'u', 'w', 'z')
}
