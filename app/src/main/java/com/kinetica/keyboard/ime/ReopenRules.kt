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

import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.keys.ShiftState

/**
 * The timestamp the first synthetic anchor of a reopened word takes, so that all [count] of
 * them land strictly before [before].
 *
 * [before] is the touch time of the letter that caused the reopen, or now for a cursor move.
 * Based on `now`, that letter's earlier touch time sorted it before the whole reloaded word
 * whenever latency exceeded the word's length in ms: `automatico` + `per` became
 * `pautomatico`, which decodes to nothing.
 */
internal fun reloadAnchorBase(before: Long, count: Int): Long = before - count - 1

/**
 * The bar for a word the cursor sits inside: as written first, then the words its own bar had
 * when this keyboard committed it, then the decode of its letters.
 */
internal fun midWordWords(decoded: List<String>, history: CommitHistory.Record?, offer: WordAround?): List<String> {
    if (history == null || offer == null) return decoded
    val out = ArrayList<String>()
    fun add(w: String) {
        if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
    }
    add(offer.head + offer.tail)
    add(history.word)
    history.alternatives.forEach(::add)
    decoded.forEach(::add)
    return out
}

/**
 * The word before the one whose first [head] characters end [before], lowercased; null at the start.
 * An elided article loses its apostrophe: the piece after `l'` follows `l`, the commit tapping made.
 */
internal fun wordBefore(before: CharSequence, head: String): String? {
    var i = before.length - head.length
    while (i > 0 && !isWordChar(before[i - 1])) i--
    var j = i
    while (j > 0 && isWordChar(before[j - 1])) j--
    if (j >= i) return null
    return before.subSequence(j, i).toString().lowercase().trimEnd('\'').ifEmpty { null }
}

/**
 * [word] as tap anchors at its keys' centres, timed to end before [beforeTime] (see
 * [reloadAnchorBase]), or null when a letter has no key on this board. Accents fold onto their
 * base keys and apostrophes are skipped: the trie puts dictionary apostrophes back for free.
 */
internal fun tapAnchors(word: String, g: KeyboardGeometry, beforeTime: Long): List<InputToken>? {
    val codes = g.alphabet.encode(AccentFolder.fold(word.lowercase())) ?: return null
    val taps = ArrayList<InputToken>(codes.size)
    val base = reloadAnchorBase(beforeTime, codes.size)
    for (i in codes.indices) {
        val code = codes[i]
        if (code == g.alphabet.apostrophe) continue
        if (!g.hasKey(code)) return null
        taps.add(
            TapToken(
                StreamId.LEFT, code, g.centerX(code), g.centerY(code),
                longPress = false, tStart = base + i, tEnd = base + i + 1,
            ),
        )
    }
    return taps.ifEmpty { null }
}

/**
 * The taps an accented letter from a popup joins the word as, at [now]: one per letter it folds
 * to, so `ß` is two taps on `s` and the decode reads `grusse` for a written `grüße`. Null when
 * [text] is no accented letter or a letter has no key on this board.
 */
internal fun accentTaps(text: String, g: KeyboardGeometry, now: Long): List<TapToken>? {
    val codes = AccentFolder.accentedLetterCodes(text, g.alphabet) ?: return null
    if (codes.any { !g.hasKey(it) }) return null
    return codes.mapIndexed { i, code ->
        TapToken(
            StreamId.LEFT, code, g.centerX(code), g.centerY(code),
            longPress = false, tStart = now + i, tEnd = now + i + 1,
        )
    }
}

/** The shift a reloaded word's alternatives are shown in, read off the word itself. */
internal fun shiftOf(word: String): ShiftState.State = when {
    word.length > 1 && word.all { it.isUpperCase() } -> ShiftState.State.CAPS_LOCK
    word.firstOrNull()?.isUpperCase() == true -> ShiftState.State.SHIFT
    else -> ShiftState.State.NONE
}

/** True when the word around the cursor, cut as [midWordPiece] cuts it, is still the one [offer] was made for. */
internal fun midWordStillThere(
    before: CharSequence,
    after: CharSequence,
    offer: WordAround,
    isWord: (String) -> Boolean,
    joins: (String) -> Boolean = { false },
): Boolean = wordAroundCursor(before, after, KineticaConstants.MAX_WORD_LEN)?.let { midWordPiece(it, isWord, joins) } == offer

/**
 * The part of [run] a reload reopens: all of it when it is a word (`don't`), else what follows the
 * last apostrophe that ended a word as it was typed. A tapped `'` ends the word unless [joins] took
 * it for the piece before it, so `i'` and `don'` come back whole, while `l'al` was typed as `l` and
 * `al` and reopens as `al`: whole, its letters would come back as `laltro` and commit `l'altro` as
 * new. A `’` never joins.
 */
internal fun reloadFragment(run: String, isWord: (String) -> Boolean, joins: (String) -> Boolean): String {
    if (run.none { it == '\'' || it == '\u2019' } || isWord(run)) return run
    var start = 0
    for (i in run.indices) {
        val c = run[i]
        if (c == '\u2019' || (c == '\'' && !joins(run.substring(start, i)))) start = i + 1
    }
    return run.substring(start)
}

/** [reloadFragment] for a run no tapped apostrophe joined: cut after its last one. */
internal fun reloadFragment(run: String, isWord: (String) -> Boolean): String = reloadFragment(run, isWord) { false }

/**
 * The run before the cursor a reload reads: [trailingLetterRun]'s, and a `’` between two letters, so a
 * word a smart-quote editor wrote (`I’d`) comes back whole. A `’` with no letter on either side is a
 * quote and ends the run, as it always did.
 */
internal fun reloadRun(before: CharSequence): String {
    var start = before.length
    while (start > 0) {
        val c = before[start - 1]
        val inner = c == '\u2019' && start >= 2 && start < before.length &&
            before[start - 2].isLetter() && before[start].isLetter()
        if (isWordChar(c) || inner) start-- else break
    }
    return before.subSequence(start, before.length).toString()
}

/**
 * The part of [word] a mid-word offer is for, by [reloadFragment]'s rule: all of it when it is a
 * word (`don't`), else the piece after its last apostrophe, split where the cursor is. Null with
 * the cursor at or before that apostrophe (`l|'altro`): the piece it sits in is no word the reload
 * would reopen either. An apostrophe that ends the run splits nothing: `dogs'` and `po'` stay whole.
 */
internal fun midWordPiece(word: WordAround, isWord: (String) -> Boolean, joins: (String) -> Boolean): WordAround? {
    val whole = word.head + word.tail
    val core = whole.trimEnd('\'')
    val piece = reloadFragment(core, isWord, joins)
    if (piece.length == core.length) return word
    val cut = core.length - piece.length
    if (word.head.length <= cut) return null
    return WordAround(word.head.substring(cut), word.tail)
}

/** [midWordPiece] for a word no tapped apostrophe joined. */
internal fun midWordPiece(word: WordAround, isWord: (String) -> Boolean): WordAround? = midWordPiece(word, isWord) { false }

/**
 * True when a user-driven selection change parks a collapsed cursor somewhere a word
 * could be reopened.
 *
 * Only the shape of the selection is decided here; whether a word precedes the cursor is
 * [WordReopen.reloadWordUnderCursor]'s question. A selection is never a reopen: the user is
 * acting on a range. Offset 0 is never one either, since nothing precedes it.
 */
internal fun reopensWordUnderCursor(selectionLength: Int, selStart: Int, selEnd: Int): Boolean =
    selectionLength == 0 && selStart == selEnd && selStart > 0
