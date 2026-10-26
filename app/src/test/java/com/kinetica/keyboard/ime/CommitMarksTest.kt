package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which commit a reload takes up again, found by where it was written, and whether its lesson is
 * armed to go back. The reload and the editor have no JVM reach; these are the rules under them,
 * walked with the offsets the editor would report.
 */
class CommitMarksTest {

    private fun lesson(word: String) = CommitLesson(word, "en", counted = true, voted = true, caseBefore = null, pair = null)

    @Test
    fun aCommitBackspacedIntoReopensWithItsLessonArmed() {
        // `farlo` swiped at 60, its automatic space, then two backspaces: the space, then the `o`.
        val marks = CommitMarks()
        val farlo = lesson("farlo")
        marks.record(60, "farlo", farlo)
        marks.spaceAt(65)
        marks.onEdit(66, 1, 0)
        val whole = marks.reopened(60, "farlo")
        assertNotNull(whole)
        assertTrue(armsLesson(whole!!, "farlo"))
        marks.onEdit(65, 1, 0)
        val left = marks.reopened(60, "farl")
        assertSame(farlo, left?.lesson)
        assertTrue("letters deleted say it was not the word", armsLesson(left!!, "farl"))
        assertNotNull(marks.reopened(60, "Far"))
    }

    @Test
    fun aWordTheUserSpacedReopensWholeWithItsLessonUnarmed() {
        // `hello` and the space key, then a backspace: still the user's word.
        val marks = CommitMarks()
        marks.record(0, "hello", lesson("hello"))
        val mark = marks.reopened(0, "hello")!!
        assertFalse(armsLesson(mark, "hello"))
        // Backspaced into, it is being changed: armed, whoever typed the space.
        assertTrue(armsLesson(mark, "hell"))
    }

    @Test
    fun theCommitBeforeTheLastReopensOnceTheLastIsGone() {
        // Captured: `Comp` at 312, its space, `a` at 317, its space, then three backspaces.
        val marks = CommitMarks()
        val comp = lesson("comp")
        marks.record(312, "Comp", comp)
        marks.spaceAt(316)
        marks.record(317, "a", lesson("a"))
        marks.spaceAt(318)
        marks.onEdit(319, 1, 0)
        assertEquals("a", marks.reopened(317, "a")?.word)
        marks.onEdit(318, 1, 0)
        marks.onEdit(317, 1, 0)
        val mark = marks.reopened(312, "Comp")
        assertSame(comp, mark?.lesson)
        assertTrue("its space was the keyboard's", armsLesson(mark!!, "Comp"))
    }

    @Test
    fun theSameWordElsewhereIsNotTheCommit() {
        val marks = CommitMarks()
        marks.record(40, "hello", lesson("hello"))
        assertNull(marks.reopened(10, "hello"))
        assertNull(marks.reopened(40, "help"))
        assertNull(marks.reopened(40, ""))
        // Longer than the commit: letters typed after it, not the commit.
        assertNull(marks.reopened(40, "hellos"))
    }

    @Test
    fun aReopenedCommitCommittedUnchangedKeepsItsLesson() {
        // `hello` reopened and committed again learns nothing, so its lesson must stay reachable.
        val marks = CommitMarks()
        val hello = lesson("hello")
        marks.record(0, "hello", hello)
        marks.record(0, "Hello", null)
        assertSame(hello, marks.reopened(0, "hel")?.lesson)
        // Another word in its place is another commit.
        marks.record(0, "help", null)
        assertNull(marks.reopened(0, "hel")?.lesson)
    }

    @Test
    fun aLessonTakenBackIsNeverArmedAgain() {
        val marks = CommitMarks()
        val fake = lesson("fake")
        marks.record(0, "fake", fake)
        marks.forget(fake)
        assertNull(marks.reopened(0, "fake"))
    }

    @Test
    fun ownEditsMoveTheMarks() {
        val marks = CommitMarks()
        marks.record(20, "word", lesson("word"))
        // Three letters typed earlier in the field.
        marks.onEdit(5, 0, 3)
        assertNotNull(marks.reopened(23, "word"))
        assertNull(marks.reopened(20, "word"))
        // A delete ending at its first letter leaves it whole.
        marks.onEdit(23, 1, 0)
        assertNotNull(marks.reopened(22, "word"))
        // One across its start drops it.
        marks.onEdit(24, 3, 0)
        assertNull(marks.newest)
    }

    @Test
    fun anEditInsideTheWordLeavesItWhereItStarts() {
        val marks = CommitMarks()
        marks.record(10, "farlo", lesson("farlo"))
        // The last two letters go, then `e` is typed: still the commit's place.
        marks.onEdit(15, 2, 0)
        marks.onEdit(13, 0, 1)
        assertNotNull(marks.reopened(10, "far"))
    }

    @Test
    fun punctuationEndsTheWordOnPurpose() {
        // `hello`, its automatic space eaten by a comma: reopened whole it is the user's.
        val marks = CommitMarks()
        marks.record(0, "hello", lesson("hello"))
        marks.spaceAt(5)
        marks.unspaceAt(5)
        assertFalse(armsLesson(marks.reopened(0, "hello")!!, "hello"))
        // A space flag only lands on the commit that ends there.
        marks.spaceAt(9)
        assertFalse(marks.newest!!.spaced)
    }

    @Test
    fun onlyTheNewestCommitReopens() {
        // `ha`, `ha`: the cursor parked after the first is not the last commit, and the context's
        // last `ha` is the second.
        val marks = CommitMarks()
        marks.record(0, "ha", lesson("ha"))
        marks.spaceAt(2)
        marks.record(3, "ha", lesson("ha"))
        marks.spaceAt(5)
        assertNull(marks.reopened(0, "ha"))
        assertNotNull(marks.reopened(3, "ha"))
    }

    @Test
    fun twoCommitsAreRememberedAndADeleteToTheFirstLetterDropsOne() {
        val marks = CommitMarks()
        marks.record(0, "one", lesson("one"))
        marks.record(4, "two", lesson("two"))
        marks.record(8, "six", lesson("six"))
        assertEquals("six", marks.newest?.word)
        // `six` backspaced letter by letter: kept while letters are left, gone with the first.
        marks.onEdit(11, 1, 0)
        marks.onEdit(10, 1, 0)
        assertEquals("six", marks.newest?.word)
        marks.onEdit(9, 1, 0)
        assertNotNull(marks.reopened(4, "two"))
        // Two deep: `one` went when `six` came.
        marks.onEdit(7, 3, 0)
        assertNull(marks.newest)
    }

    @Test
    fun aCommitOverAnOlderOneReplacesIt() {
        val marks = CommitMarks()
        marks.record(4, "two", lesson("two"))
        marks.record(8, "six", lesson("six"))
        marks.record(4, "twofold", lesson("twofold"))
        assertEquals("twofold", marks.newest?.word)
        marks.onEdit(11, 7, 0)
        assertNull("deleted to its first letter", marks.newest)
    }

    @Test
    fun noLessonNothingArmed() {
        // A commit that taught nothing (a private field) still reopens, for the decode context.
        val marks = CommitMarks()
        marks.record(0, "hello", null)
        marks.spaceAt(5)
        val mark = marks.reopened(0, "hel")
        assertNotNull(mark)
        assertFalse(armsLesson(mark!!, "hel"))
    }
}
