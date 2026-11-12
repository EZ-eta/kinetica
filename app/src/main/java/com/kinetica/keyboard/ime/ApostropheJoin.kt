package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.WordPredictor

/**
 * Languages whose apostrophe elides: `l'altro` is two words, so a tapped one never joins there. Their
 * lists' apostrophe rows are elisions or junk (`l'ai`, `n'roll`), and a learned elision (`l'ora`) would
 * leave a swipe after `l'` only itself to decode against.
 */
internal val ELIDING_LANGUAGES = setOf("it", "fr")

/**
 * Whether a tapped apostrophe joins [word], the word in hand as written: when [word] + `'` can still
 * become a word of a resident that writes contractions as one word, list and learned rows alike
 * (`i'd`, `don't`, a learned `y'all`). Otherwise the apostrophe ends the word, as it always did.
 *
 * [residents] are the resident languages with their lexicons; one in another script than [alphabet],
 * the board's, has no say.
 *
 * [elidingOn]: Italian or French is enabled. Then only the bundled lists decide: learned rows of
 * theirs filed to English (`l'ora`) would make `l'altro` one English word.
 */
internal fun apostropheJoins(
    word: String,
    residents: List<Pair<String, WordPredictor>>,
    alphabet: Alphabet,
    elidingOn: Boolean,
): Boolean {
    if (word.isEmpty() || alphabet.apostrophe < 0) return false
    val prefix = "$word'"
    return residents.any { (lang, p) ->
        lang !in ELIDING_LANGUAGES && p.alphabet === alphabet &&
            if (elidingOn) p.holdsListPrefix(prefix) else p.isLivePrefix(prefix)
    }
}

/** Whether [languages], the enabled ones, include one whose apostrophe elides. */
internal fun elidingEnabled(languages: Collection<String>): Boolean = languages.any { it in ELIDING_LANGUAGES }

/**
 * The words an apostrophe elides in [ELIDING_LANGUAGES]: what may stand before `'` in `nell'altro`,
 * `qu'il`. The lists split elisions, so they hold these as plain words and cannot say which ones
 * elide. A swipe cannot show a double letter, so `nel` outranks `nell` and only this set tells them
 * apart. `po` and `mo` are truncations (`un po'`).
 */
internal val ELISION_HEADS: Map<String, Set<String>> = mapOf(
    "it" to setOf(
        "l", "un", "c", "d", "m", "t", "s", "v", "n", "gl",
        "dell", "nell", "all", "dall", "sull", "coll", "pell", "quell", "quest", "bell", "sant", "grand",
        "tutt", "senz", "com", "dov", "anch", "po", "mo",
    ),
    "fr" to setOf(
        "l", "d", "j", "m", "t", "s", "n", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "presqu", "quelqu",
    ),
)

/** The elision heads of the eliding languages among [languages]; empty when none is enabled. */
internal fun elisionHeadsFor(languages: Collection<String>): Set<String> =
    languages.flatMap { ELISION_HEADS[it].orEmpty() }.toSet()

/**
 * The word to write before a `'` a swipe crossed, from the decode's first [ELISION_RANKS]
 * [candidates] in rank order: the best-ranked elision head (`nell` over `nel`); else the longest
 * head that begins one of them (`quest` of `questo`, `c` of `con`: a run-out from a far letter to the
 * key crosses others). Null when [heads] is empty or nothing fits: the lead stands.
 */
internal fun elisionHead(candidates: List<String>, heads: Set<String>): String? {
    if (heads.isEmpty()) return null
    val top = candidates.take(ELISION_RANKS).map { it.lowercase() }
    top.firstOrNull { it in heads }?.let { return it }
    return top
        .flatMap { w -> (1 until w.length).map { w.substring(0, it) } }
        .filter { it in heads }
        .maxByOrNull { it.length }
}

/**
 * Candidates read for a head: the true one ranked second or third on every cut golden; reading the
 * whole list took a deep `quell` for `quest`. Measured on 15 elisions at three offsets.
 */
internal const val ELISION_RANKS = 3
