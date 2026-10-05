package com.kinetica.keyboard.ime

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
    fun anAccentedWordMatchesItselfAndNotItsFoldedForm() {
        // The seed is the editor's own text, accents included (that divergence is
        // the shipped pattern), so a re-commit of "perché" matches. Its folded
        // spelling is a different word and learns.
        assertFalse(learnsOnCommit("perché", "perché"))
        assertTrue(learnsOnCommit("perche", "perché"))
    }
}
