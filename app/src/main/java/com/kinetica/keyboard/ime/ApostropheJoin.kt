/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

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
 * (`i'd`, `don't`, a learned `y'all`), or [word] is a list word that takes `'s` there (`marco's`).
 * Otherwise the apostrophe ends the word, as it always did.
 *
 * [residents] are the resident languages with their lexicons; one in another script than [alphabet],
 * the board's, has no say.
 *
 * [elidingOn]: Italian or French is enabled. Then only the bundled lists decide: learned rows of
 * theirs filed to English (`l'ora`) would make `l'altro` one English word. [heads], their elision
 * heads, never take `'s`: `all'` and `l'` stay elisions.
 */
internal fun apostropheJoins(
    word: String,
    residents: List<Pair<String, WordPredictor>>,
    alphabet: Alphabet,
    elidingOn: Boolean,
    heads: Set<String> = emptySet(),
): Boolean {
    if (word.isEmpty() || alphabet.apostrophe < 0) return false
    val prefix = "$word'"
    return residents.any { (lang, p) ->
        lang !in ELIDING_LANGUAGES && p.alphabet === alphabet &&
            (
                (if (elidingOn) p.holdsListPrefix(prefix) else p.isLivePrefix(prefix)) ||
                    lang in POSSESSIVE_LANGUAGES && word.lowercase() !in heads && p.takesPossessive(word)
                )
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

/**
 * What a committed [word] is learned as: itself, or, when the decode joined an elision over the
 * apostrophe key at [joinAt], the head and the word after it (`nell'altro` is `nell` and `altro`), as a
 * tapped `'` would have written them.
 */
internal fun learnedParts(word: String, joinAt: Int): List<String> =
    if (joinAt in 1 until word.length - 1 && word[joinAt] == '\'') {
        listOf(word.substring(0, joinAt), word.substring(joinAt + 1))
    } else {
        listOf(word)
    }

/** Languages whose words take `'s` beyond the list's own spellings, swiped through the apostrophe key. */
internal val POSSESSIVE_LANGUAGES: Set<String> = setOf("en")

/** The elision heads of the eliding languages among [languages]; empty when none is enabled. */
internal fun elisionHeadsFor(languages: Collection<String>): Set<String> =
    languages.flatMap { ELISION_HEADS[it].orEmpty() }.toSet()

/**
 * Whether a `'` tapped with no word in hand while the other thumb, down before it, still draws starts that
 * stroke's word ([strokes], the apostrophe in strokes, on). Off, it is text, as a tap always was.
 */
internal fun apostropheRides(strokes: Boolean, wordPending: Boolean, otherThumbDown: Boolean): Boolean =
    strokes && !wordPending && otherThumbDown

/** Whether a word with a swipe in it may join a tapped `'`: only with the apostrophe in strokes on. */
internal fun swipedWordMayJoin(strokes: Boolean, hasSwipe: Boolean): Boolean = strokes || !hasSwipe

/**
 * Whether an undecodable word ending on a joined `'` holds the stale timeout off until its `s` comes:
 * only with the apostrophe in strokes on, the one way such a word is made.
 */
internal fun waitsForApostropheS(strokes: Boolean, endsOnTappedApostrophe: Boolean): Boolean =
    strokes && endsOnTappedApostrophe

/**
 * The apostrophe in strokes on [p], as the keyboard sets it at load: its language's elision heads, `'s`, a
 * `'` riding a stroke. [on] false leaves all three off, so `'` is read as a tap only.
 */
internal fun strokeApostrophes(p: WordPredictor, on: Boolean) {
    p.elisionHeads = if (on) elisionHeadsFor(listOf(p.language)) else emptySet()
    p.possessive = on && p.language in POSSESSIVE_LANGUAGES
    p.ridesApostrophes = on
}
