package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expandify trigger walk.
 *
 * A fourth walk rather than a fifth caller of trailingLetterRun, and these fixtures are
 * why: the reporter's own five triggers are `.`, `x`, `vv`, `^^` and `(-.-)'`, and the
 * letter walk returns nothing for three of them. Whitespace is the only boundary here.
 */
class TriggerAtCursorTest {

    private fun at(before: String) = triggerAtCursor(before, MAX_TRIGGER_CHARS)

    @Test
    fun theReportersOwnTriggersAllSurvive() {
        assertEquals("x", at("x").trigger)
        assertEquals("vv", at("vv").trigger)
        assertEquals("^^", at("^^").trigger)
        assertEquals(".", at(".").trigger)
        assertEquals("(-.-)'", at("(-.-)'").trigger)
        assertEquals("Laziness", at("Laziness").trigger)
    }

    @Test
    fun aTriggerIsTakenFromTheEndOfARealSentence() {
        assertEquals("vv", at("shopping list vv").trigger)
        assertEquals("^^", at("feeling ^^").trigger)
    }

    @Test
    fun oneTrailingSpaceIsSkippedAndReportedInTheSpan() {
        // The autospace writes one after almost every word, and a trigger that stops
        // working the moment it arrives is a trigger nobody can fire.
        val found = at("done vv ")
        assertEquals("vv", found.trigger)
        assertEquals(3, found.span)
    }

    @Test
    fun twoSpacesAreABoundaryNotALicence() {
        // A finished thought, not a word waiting to be expanded.
        assertEquals("", at("done vv  ").trigger)
        assertEquals("", at("done vv\n").trigger)
    }

    @Test
    fun theSpanIsTheTriggerWhenNoSpaceFollows() {
        val found = at("hello ^^")
        assertEquals("^^", found.trigger)
        assertEquals(2, found.span)
    }

    @Test
    fun anEmptyOrBlankContextYieldsNothing() {
        assertEquals("", at("").trigger)
        assertEquals("", at(" ").trigger)
        assertEquals(0, at("").span)
    }

    @Test
    fun theWalkIsBounded() {
        // Long enough to matter: without the bound a paragraph with no space in it would
        // be read back in full on every fire.
        val long = "a".repeat(MAX_TRIGGER_CHARS + 20)
        assertEquals(MAX_TRIGGER_CHARS, at(long).trigger.length)
    }

    @Test
    fun aNewlineBoundsItLikeASpace() {
        assertEquals("vv", at("first line\nvv").trigger)
    }

    // ---- what gets written into a field that holds one line ---------------------------

    @Test
    fun aMultiLineTargetIsFlattenedInASingleLineField() {
        assertEquals("a b c", expansionForField("a\nb\nc", multiline = false))
        assertEquals("a b", expansionForField("a\r\nb", multiline = false).replace("  ", " "))
    }

    @Test
    fun aMultiLineTargetIsKeptWhereTheFieldTakesOne() {
        assertEquals("a\nb\nc", expansionForField("a\nb\nc", multiline = true))
    }
}
