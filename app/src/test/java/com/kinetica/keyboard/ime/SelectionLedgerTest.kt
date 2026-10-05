package com.kinetica.keyboard.ime

import com.kinetica.keyboard.ime.SelectionLedger.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which cursor reports are the keyboard's own. A count of reports to swallow crept up
 * on same-length rewrites and ate the user's moves, eight at once in one capture.
 */
class SelectionLedgerTest {

    @Test
    fun aRewriteThatMovesNothingDoesNotEatTheNextMove() {
        // Captured: `no` + `t` is committed, then the merge rewrites it to `not` in place.
        val l = SelectionLedger()
        l.expect(10, 10, now = 0)
        assertEquals(Verdict.OWN, l.judge(10, 10, now = 20))
        l.expect(10, 10, now = 40) // same length: the editor sends nothing
        assertEquals(Verdict.USER, l.judge(4, 4, now = 2_000))
    }

    @Test
    fun aLongFieldStillGivesTheFirstPlacement() {
        // Captured: 99 commits in one field, then eight placements swallowed.
        val l = SelectionLedger()
        var t = 0L
        var cursor = 0
        repeat(99) { i ->
            cursor += 5
            l.expect(cursor, cursor, t)
            assertEquals(Verdict.OWN, l.judge(cursor, cursor, t + 15))
            if (i % 2 == 0) l.expect(cursor, cursor, t + 30) // a same-length rewrite
            t += 400
        }
        assertEquals(Verdict.USER, l.judge(120, 120, t + 1_000))
    }

    @Test
    fun chainedEditsReportOnce() {
        val l = SelectionLedger()
        l.expect(11, 11, now = 0)
        l.expect(12, 12, now = 1)
        assertEquals(Verdict.OWN, l.judge(12, 12, now = 30))
        assertEquals(0, l.pending)
        assertEquals(Verdict.USER, l.judge(3, 3, now = 1_000))
    }

    @Test
    fun aPasteClaimsOnlyTheReportThatFollowsIt() {
        val l = SelectionLedger()
        l.expectAny(now = 0)
        assertEquals(Verdict.OWN, l.judge(40, 40, now = 60))
        l.expectAny(now = 1_000) // a copy moves nothing and gets no report
        assertEquals(Verdict.USER, l.judge(7, 7, now = 2_000))
    }

    @Test
    fun anEditorThatRewritesTextDoesNotAbandonTheWord() {
        // A report right after an own edit, at a position not predicted, is still the edit's.
        val l = SelectionLedger()
        l.expect(20, 20, now = 0)
        assertEquals(Verdict.MISMATCH, l.judge(21, 21, now = 50))
        assertEquals(Verdict.USER, l.judge(5, 5, now = 1_000))
    }

    @Test
    fun anOldExpectationMatchesNothing() {
        val l = SelectionLedger()
        l.expect(8, 8, now = 0)
        assertEquals(Verdict.USER, l.judge(8, 8, now = 10_000))
        l.clear()
        assertEquals(null, l.lastExpected())
    }
}
