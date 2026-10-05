package com.kinetica.keyboard.keys

/**
 * Which single letters are words on their own, per language.
 *
 * A curated list, not a dictionary test: every letter a-z is in every bundled wordlist, with
 * large frequencies (`en` holds `l` at 126 518, `t` at 72 881). They are OpenSubtitles artefacts
 * (split contractions, initials, list markers), so `WordPredictor.isWord` cannot tell a lone `t`
 * from a lone `a`.
 *
 * Kept beside [AutoCapitalization] because the language decides here too, not the position: `e`
 * is a word in Italian and not in English, `y` in Spanish and nowhere else here.
 *
 * [lang] is the active language code, matching [AutoCapitalization.forWord]. Cost: with per-word
 * auto-detect on, Italian typed while English is active gets the English set, so an Italian `e`
 * does not space. A union of the enabled languages would import each language's risk into the
 * others, and a wrong fire costs a space the user has to delete.
 *
 * A one-letter word is weaker evidence than a word, so `autospacesTappedWord` waits longer before
 * spacing it.
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
     * The letters. Each set is a closed list of function words, not a judgement about frequency:
     *
     *  - `en`: the article `a` and the pronoun `I`. `AutoCapitalization` turns a lone `i` into
     *    `I`, so the pronoun spaces and capitalizes together.
     *  - `it`: `a` (to), `e` (and), `i` (the, masculine plural), `o` (or). `è` (is) folds onto
     *    `e`.
     *  - `es`: `a` (to), `e` and `y` (and), `o` (or).
     *  - `pl`: `a`, `i` (and), `o` (about), `u` (at), `w` (in), `z` (with), and the
     *    interjection `e`, from a Polish speaker's report. Timing unmeasured: `w` and `z` are
     *    also common word starts and `e` is colloquial, so the set may want a longer delay.
     *  - `cs`: conjunctions `a`, `i` and prepositions `k`, `o`, `s`, `u`, `v`, `z`, the
     *    one-letter function words listed by ÚJČ: https://prirucka.ujc.cas.cz/?id=880. Timing
     *    unmeasured, as for Polish.
     *  - `nl`: `u`, the formal pronoun. The clitics `'t`, `'s` and `'n` lead with an apostrophe,
     *    so they are not single letters and the generator's word shape rejects them anyway.
     *  - `de`: empty. German has no one-letter word, and an unregistered language falls back to
     *    [EN], which would space and capitalize a lone `a` or `i` mid-word.
     *  - `fr`: `a` (has) and `y` (there). `à` (to) folds onto `a`.
     *  - `no`: `i` (in), `å` (the infinitive marker, which folds onto `a`) and `o` (the
     *    dialectal `og`). No native-speaker report behind it, so it is the narrowest reading.
     *  - `ru`: the one-letter prepositions and conjunctions а в и к о с у я.
     *  - `uk`: а в з і й о у я. Ukrainian writes і for "and" and з for "with".
     *  - `he`, `ar`: empty. Their one-letter words (Hebrew ו ה ב ל מ ש כ, Arabic و ب ل ف) are
     *    written joined to the next word, so a lone one is still being typed.
     */
    private fun setFor(lang: String): Set<Char> = when (lang) {
        "it" -> IT
        "es" -> ES
        "pl" -> PL
        "cs" -> CS
        "nl" -> NL
        "de" -> DE
        "fr" -> FR
        "no" -> NO
        "ru" -> RU
        "uk" -> UK
        "he" -> HE
        "ar" -> AR
        else -> EN
    }

    private val EN = setOf('a', 'i')
    private val IT = setOf('a', 'e', 'i', 'o')
    private val ES = setOf('a', 'e', 'o', 'y')
    private val PL = setOf('a', 'e', 'i', 'o', 'u', 'w', 'z')
    private val CS = setOf('a', 'i', 'k', 'o', 's', 'u', 'v', 'z')
    private val NL = setOf('u')
    private val DE = emptySet<Char>()
    private val FR = setOf('a', 'y')
    private val NO = setOf('a', 'i', 'o')
    private val RU = setOf('а', 'в', 'и', 'к', 'о', 'с', 'у', 'я')
    private val UK = setOf('а', 'в', 'з', 'і', 'й', 'о', 'у', 'я')
    private val HE = emptySet<Char>()
    private val AR = emptySet<Char>()
}
