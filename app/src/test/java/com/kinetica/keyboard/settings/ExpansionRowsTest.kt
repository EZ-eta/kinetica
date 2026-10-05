package com.kinetica.keyboard.settings

import com.kinetica.keyboard.data.Expansion
import com.kinetica.keyboard.ime.MAX_TRIGGER_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expansion list's rules. Its reporter describes "hundreds if not thousands" of
 * expansions, so finding one and refusing an unusable one are the two that matter.
 */
class ExpansionRowsTest {

    private fun rows() = listOf(
        Expansion("vv", 0, "✅"),
        Expansion("Today", 0, "Today:\n• \n• "),
        Expansion(".", 0, "•"),
        Expansion("perché", 0, "because"),
    )

    @Test
    fun rowsSortAlphabeticallyIgnoringCase() {
        // Not by influence, unlike the personal word list: nothing here competes with
        // anything, so the only question is where the one you wrote is.
        assertEquals(
            listOf(".", "perché", "Today", "vv"),
            ExpansionRows.sortedForDisplay(rows()).map { it.trigger },
        )
    }

    @Test
    fun theFilterSearchesTheTargetAsWellAsTheTrigger() {
        // People remember what an expansion produces more often than the shorthand they picked
        // for it. "because" appears in no trigger at all.
        assertEquals(listOf("perché"), ExpansionRows.filtered(rows(), "because").map { it.trigger })
        // And both rows whose target carries a bullet come back, neither of which has one
        // in its trigger except by coincidence.
        assertEquals(setOf("Today", "."), ExpansionRows.filtered(rows(), "•").map { it.trigger }.toSet())
    }

    @Test
    fun theFilterFoldsAccentsLikeTheDecoderDoes() {
        assertEquals(listOf("perché"), ExpansionRows.filtered(rows(), "perche").map { it.trigger })
    }

    @Test
    fun aBlankQueryIsTheWholeList() {
        assertEquals(4, ExpansionRows.filtered(rows(), "   ").size)
    }

    @Test
    fun aTriggerWithWhitespaceIsRefused() {
        // The cursor walk stops at whitespace, so such a trigger could never fire.
        assertFalse(ExpansionRows.isValidTrigger("two words", MAX_TRIGGER_CHARS))
        assertFalse(ExpansionRows.isValidTrigger("a\tb", MAX_TRIGGER_CHARS))
        assertFalse(ExpansionRows.isValidTrigger("", MAX_TRIGGER_CHARS))
        assertFalse(ExpansionRows.isValidTrigger("x".repeat(MAX_TRIGGER_CHARS + 1), MAX_TRIGGER_CHARS))
    }

    @Test
    fun theKeyboardsOwnSpaceIsRemovedNotRefused() {
        // #19: the automatic space after a typed trigger used to refuse the save with a toast.
        assertEquals("degc", ExpansionRows.cleanTrigger("degc "))
        assertEquals("andor", ExpansionRows.cleanTrigger(" and\tor\n"))
        assertTrue(ExpansionRows.isValidTrigger(ExpansionRows.cleanTrigger("degc "), MAX_TRIGGER_CHARS))
        assertEquals("^^", ExpansionRows.cleanTrigger("^^"))
    }

    @Test
    fun aTriggerThatIsOnlySpaceIsStillBlank() {
        assertFalse(ExpansionRows.isValidTrigger(ExpansionRows.cleanTrigger("   "), MAX_TRIGGER_CHARS))
    }

    @Test
    fun theReportersOwnTriggersAreAllValid() {
        for (t in listOf("x", "vv", "^^", ".", "(-.-)'", "Laziness")) {
            assertTrue(t, ExpansionRows.isValidTrigger(t, MAX_TRIGGER_CHARS))
        }
    }

    @Test
    fun aMultiLineTargetPreviewsOnOneLine() {
        // Rendered as itself a five-line block is five lines tall and pushes every other
        // row off the screen.
        assertEquals("Today: ⏎ •  ⏎ • ", ExpansionRows.preview("Today:\n• \n• ", 40))
        assertEquals("aaaa…", ExpansionRows.preview("a".repeat(20), 5))
    }

    @Test
    fun anActionTargetShowsItsNameNotItsReservedString() {
        val label = ExpansionRows.shown("action:paste", 40) { "Pastes" }
        assertEquals("Pastes", label)
        assertEquals("•", ExpansionRows.shown("•", 40) { "never" })
        // A key combination by its name, not its stored `combo:` text.
        assertEquals("Ctrl+A", ExpansionRows.shown("combo:ctrl+a", 40) { "never" })
    }

    private fun e(t: String, p: Int, v: String) = com.kinetica.keyboard.data.Expansion(t, p, v)

    @Test
    fun aSecondTargetForATriggerIsAddedNotReplaced() {
        // #19 phase 2: one trigger, several targets, picked on the bar.
        val rows = listOf(e("mail", 0, "a@x.it"))
        assertEquals(mapOf("mail" to listOf("a@x.it", "b@y.com")), ExpansionRows.afterSave(rows, null, "mail", "b@y.com"))
        assertEquals(mapOf("mail" to listOf("a@x.it")), ExpansionRows.afterSave(rows, null, "mail", "a@x.it"))
        assertEquals(mapOf("new" to listOf("x")), ExpansionRows.afterSave(rows, null, "new", "x"))
    }

    @Test
    fun anEditTouchesItsOwnRowOnly() {
        val rows = listOf(e("mail", 0, "a"), e("mail", 1, "b"), e("sig", 0, "s"))
        assertEquals(mapOf("mail" to listOf("a", "B")), ExpansionRows.afterSave(rows, rows[1], "mail", "B"))
        assertEquals(
            mapOf("mail" to listOf("a"), "sig" to listOf("s", "b")),
            ExpansionRows.afterSave(rows, rows[1], "sig", "b"),
        )
        assertEquals(listOf("b"), ExpansionRows.afterDelete(rows, rows[0]))
        assertEquals(emptyList<String>(), ExpansionRows.afterDelete(rows, rows[2]))
        assertEquals(listOf("a", "b"), ExpansionRows.targetsOf(rows.reversed(), "mail"))
    }

    @Test
    fun twoTargetsThatPreviewAlikeStillPickTheirOwn() {
        assertEquals(listOf("Best…", "Best… (2)", "x", "Best… (3)"), ExpansionRows.distinctLabels(listOf("Best…", "Best…", "x", "Best…")))
    }
}
