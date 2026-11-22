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

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a commit adds a unit of personal weight.
 *
 * Deleting only the space after "hello" reloads the word into the composer as tap anchors, so
 * continued typing corrects it, and the next delimiter commits it again: one authored word would
 * earn two units. PERSONAL_MERGE_MIN_COUNT and the boost fade were tuned against honest counts,
 * and silent inflation is the drift the merge floor exists to prevent.
 *
 * Suppressing learning that should happen is the opposite failure, so both directions are pinned.
 */
class LearnOnCommitTest {

    @Test
    fun aWordTypedFromNothingIsAlwaysLearned() {
        // The common path: no reload happened, so nothing to compare.
        assertTrue(learnsOnCommit("hello", null))
        assertTrue(learnsOnCommit("", null))
        assertTrue(learnsOnCommit("don't", null))
    }

    @Test
    fun recommittingTheReloadedWordUnchangedDoesNotLearnItAgain() {
        // The reported inflation: delete the space, put it back.
        assertFalse(learnsOnCommit("hello", "hello"))
        assertFalse(learnsOnCommit("don't", "don't"))
    }

    @Test
    fun editingTheReloadedWordStillLearns() {
        // Backspacing into "hell" and typing "hello" seeds "hell" and commits "hello": a
        // different word, so it counts. Suppressing this would stop learning after any backspace.
        assertTrue(learnsOnCommit("hello", "hell"))
        assertTrue(learnsOnCommit("hell", "hello"))
        assertTrue(learnsOnCommit("help", "hello"))
        // Including the case where a swipe decode or autocorrect replaced it.
        assertTrue(learnsOnCommit("there", "the"))
    }

    @Test
    fun theComparisonIgnoresCase() {
        // displayWord applies the captured shift state and AutoCapitalization, so the committed
        // word can differ from the seeded fragment in case alone (sentence start, or English's
        // lone "i"). That is the same word.
        assertFalse(learnsOnCommit("Hello", "hello"))
        assertFalse(learnsOnCommit("hello", "Hello"))
        assertFalse(learnsOnCommit("HELLO", "hello"))
        assertFalse(learnsOnCommit("I", "i"))
    }

    @Test
    fun anEmptyReloadIsNotTreatedAsAMatch() {
        // reloadWordUnderCursor returns early on an empty fragment, so a seeded empty string
        // should not exist; if it ever did, it must not suppress a real word.
        assertTrue(learnsOnCommit("hello", ""))
        // And an empty commit against an empty seed is a no-op either way; the
        // caller already guards on word.isNotEmpty.
        assertFalse(learnsOnCommit("", ""))
    }

    @Test
    fun anAutospaceCommitTypedOnIntoAnotherWordIsTakenBack() {
        // `Fake` spaced automatically, the space taken back, then `CompanyName`: the commit of
        // `Fake` was not a word the user wrote, so its count and its capital go back.
        assertTrue(takesBackReopened("fake", "FakeCompanyName"))
        // `car`, spaced, then `pet`.
        assertTrue(takesBackReopened("car", "carpet"))
        // An elision's piece: `al` spaced, then `tro`.
        assertTrue(takesBackReopened("al", "altro"))
    }

    @Test
    fun anAutospaceCommitCommittedAgainUnchangedStaysLearnedOnce() {
        // The space deleted and put back: the word was meant, and learnsOnCommit already keeps
        // the second commit from counting.
        assertFalse(takesBackReopened("hello", "hello"))
        assertFalse(takesBackReopened("fake", "Fake"))
        // Nothing reopened, the ordinary commit, and nothing committed.
        assertFalse(takesBackReopened(null, "hello"))
        assertFalse(takesBackReopened("fake", ""))
    }

    @Test
    fun aCommitBackspacedIntoAndRetypedStaysLearnedOnce() {
        // `hello`, backspaced to `hell`, then `o`: the reload's letters are `hell`, so
        // learnsOnCommit alone would count the same word twice.
        assertTrue(learnsOnCommit("hello", "hell"))
        assertTrue(keepsReopened("hello", "hello"))
        assertTrue(keepsReopened("fake", "Fake"))
        assertFalse(takesBackReopened("hello", "hello"))
        // Changed into another word, it goes back and the new one is learned.
        assertFalse(keepsReopened("hello", "help"))
        assertTrue(takesBackReopened("hello", "help"))
        // Nothing reopened: the ordinary rules.
        assertFalse(keepsReopened(null, "hello"))
    }

    @Test
    fun anAccentedWordMatchesItselfAndNotItsFoldedForm() {
        // The seed is the editor's own text, accents included (that divergence is
        // the shipped pattern), so a re-commit of "perché" matches. Its folded
        // spelling is a different word and learns.
        assertFalse(learnsOnCommit("perché", "perché"))
        assertTrue(learnsOnCommit("perche", "perché"))
    }

    @Test
    fun aLongWordIsLearnable() {
        // Compounds run past the bundled lists' 20 letters; only the trie's cap refuses one.
        assertTrue(learnable("supercalifragilistichespiralidoso"))
        assertTrue(learnable("a".repeat(KineticaConstants.MAX_WORD_LEN)))
        assertFalse(learnable("a".repeat(KineticaConstants.MAX_WORD_LEN + 1)))
        assertFalse(learnable("e con"))
    }
}
