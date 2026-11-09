package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar's bold names the word a space writes. A bold head that space did not write was the
 * report: tapped letters are kept unless autocorrect fires, so the decode's lead was bold while the
 * letters went in.
 */
class SpaceCommitWordTest {

    private fun space(
        tentative: String = "teh",
        swipeLead: String? = null,
        hasSwipe: Boolean = false,
        literal: String = "teh",
        privateMode: Boolean = false,
        addressField: Boolean = false,
        selection: Int = 0,
        asWritten: Boolean = false,
        target: String? = null,
    ): String = spaceCommitWord(tentative, swipeLead, hasSwipe, literal, privateMode, addressField, selection, asWritten) { target }

    private fun gate(
        hasSwipe: Boolean = false,
        literal: String = "teh",
        privateMode: Boolean = false,
        addressField: Boolean = false,
        selection: Int = 0,
    ): Boolean = tapCommitMayCorrect(hasSwipe, literal, privateMode, addressField, selection)

    @Test
    fun tappedLettersAreWrittenAsTypedUnlessAutocorrectFires() {
        assertEquals("teh", space())
        assertEquals("the", space(target = "the"))
    }

    @Test
    fun aSwipeWritesItsLeadOrTheWordOnScreen() {
        assertEquals("then", space(tentative = "th", swipeLead = "then", hasSwipe = true, literal = "", target = "x"))
        // No lead: the stale partial on screen is what a space leaves.
        assertEquals("th", space(tentative = "th", swipeLead = null, hasSwipe = true, literal = ""))
    }

    @Test
    fun aReloadedWordAndAPrivateFieldAreNeverCorrected() {
        assertEquals("teh", space(asWritten = true, target = "the"))
        assertEquals("teh", space(privateMode = true, target = "the"))
        assertEquals("teh", space(literal = "", target = "the"))
    }

    @Test
    fun theCommitGateIsTheBoldsGate() {
        assertTrue(gate())
        assertFalse(gate(hasSwipe = true))
        assertFalse(gate(literal = ""))
        assertFalse(gate(privateMode = true))
        assertFalse(gate(addressField = true))
        assertFalse(gate(selection = 7))
    }

    @Test
    fun anAddressOrASelectedCompletionIsWrittenAsTyped() {
        // Firefox's address bar: `eb` typed, the app selects `ay.com/` after the cursor, the
        // decode leads `be`. Enter must leave `eb` and the completion for the app (#52).
        assertEquals("eb", space(tentative = "eb", literal = "eb", addressField = true, target = "be"))
        assertEquals("eb", space(tentative = "eb", literal = "eb", selection = 7, target = "be"))
        // An email field is an address field too; a plain field with no selection still corrects.
        assertEquals("be", space(tentative = "eb", literal = "eb", target = "be"))
        assertFalse(commitMayRewrite(addressField = true, selection = 0))
        assertFalse(commitMayRewrite(addressField = false, selection = 1))
        assertTrue(commitMayRewrite(addressField = false, selection = 0))
        // A swipe's lead is the decode, not a correction: it still writes in an address field.
        assertEquals("ebay", space(tentative = "eb", swipeLead = "ebay", hasSwipe = true, literal = "", addressField = true))
    }

    @Test
    fun theCommitAndTheBoldReadTheFieldAndTheSelection() {
        // The service has no JVM reach: both callers pass the field's kind and the live selection,
        // and the commit leaves the case alone where it may not rewrite.
        val commit = ImeSources.declaring("fun finalizePendingWord(").text
        assertTrue(commit.contains("commitMayRewrite(ime.editorState.addressField, selection)"))
        assertTrue(commit.contains("ime.editorState.addressField, selection,"))
        assertTrue(commit.contains("ime.wordState.caseChosen || !rewrites"))
        val bar = ImeSources.declaring("private fun spaceWord(").text
        assertTrue(bar.contains("addressField = ime.editorState.addressField,"))
        assertTrue(bar.contains("selection = ime.selectionLength(),"))
    }

    @Test
    fun boldFindsTheWordCaseAsideOrNothing() {
        val bar = listOf("the", "teh", "then")
        assertEquals(1, boldIndex(bar, "teh"))
        assertEquals(0, boldIndex(bar, "The"))
        assertEquals(-1, boldIndex(bar, "tea"))
        assertEquals(-1, boldIndex(bar, null))
        assertEquals(-1, boldIndex(bar, ""))
    }
}
