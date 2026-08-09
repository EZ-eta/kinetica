package com.kinetica.keyboard.keys

/**
 * Language-specific capitalization that does not come from the shift state.
 *
 * The keyboard's case handling is otherwise entirely positional - [ShiftState]
 * decides case from taps and from the editor's caps mode, and knows nothing
 * about which word is being typed. English's lone first-person pronoun is the
 * one case where the LANGUAGE, not the position, decides: "i" written alone is
 * always "I", at any point in a sentence.
 *
 * This is deliberately not a dictionary or autocorrect rule. Autocorrect never
 * touches a word that is already in the dictionary (which "i" is, in
 * `en_wordlist`), so it cannot reach this, and adding a capitalized form to the
 * asset would break the folded trie's lowercase invariant.
 *
 * Kept as a pure function in its own file because the plumbing that calls it
 * lives in `KineticaIME`, which has no JVM test source set - this way the rule
 * itself is test-locked even though its wiring is device-verified.
 */
object AutoCapitalization {

    /**
     * [word]'s spelling after any language-mandated capitalization, or [word]
     * unchanged. [lang] is the ACTIVE language code: "i" is a real word in
     * Italian (the plural masculine article) and in Spanish loanwords, so the
     * rule must not fire there.
     *
     * Applied to the word as a unit, so "in" and "i.e."'s "i" are different
     * cases - the second one still becomes "I", which is the accepted cost of
     * the rule and matches what every other keyboard does.
     */
    fun forWord(word: String, lang: String): String =
        if (lang == "en" && word.length == 1 && (word[0] == 'i' || word[0] == 'I')) "I" else word
}
