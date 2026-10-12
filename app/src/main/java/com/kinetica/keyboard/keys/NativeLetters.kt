package com.kinetica.keyboard.keys

/**
 * The accented letters each language writes, for the setting that hides accented letters on
 * long-press.
 *
 * That setting trims the active board, and only an English board has accents that are not its
 * own. Trimmed to the active board alone, a Polish writer who keeps English active would lose ą
 * and ł although Polish is enabled, so the letters of every enabled language stay. A curated
 * list, not the languages' own boards: those carry foreign accents too, Polish's `a` offers `à`.
 */
object NativeLetters {

    /** Lowercase; each letter is in its language's bundled wordlist (NativeLettersTest). */
    private val LETTERS: Map<String, String> = mapOf(
        "it" to "àèéìòù",
        "es" to "áéíóúüñ",
        "pl" to "ąćęłńóśźż",
        "cs" to "áčďéěíňóřšťúůýž",
        "nl" to "éèëïöü",
        "de" to "äöüß",
        "fr" to "àâçéèêëîïôœùûü",
        "no" to "æøåé",
    )

    /** The letters [lang] writes; empty for English and for a language on its own script. */
    fun of(lang: String): String = LETTERS[lang].orEmpty()

    /** Every letter the enabled [languages] write, as alternates spell them. */
    fun keptBy(languages: List<String>): Set<String> =
        languages.flatMap { lang -> of(lang).map { it.toString() } }.toSet()
}
