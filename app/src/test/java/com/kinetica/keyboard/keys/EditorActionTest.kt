package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reserved-output parsing shared by the comma key and the chord shortcuts. It keeps the
 * chord path from inserting `action:paste` into the document as literal text.
 */
class EditorActionTest {

    @Test
    fun everyActionRoundTripsThroughItsOutput() {
        for (a in EditorAction.entries) {
            assertEquals(a, EditorAction.of(a.output))
        }
    }

    @Test
    fun ordinaryTextIsNotAnAction() {
        // Including text that mentions one, and text that would be a plausible chord expansion.
        for (s in listOf(
            "", " ", "paste", "copy", "cut", "select_all", "Paste",
            "action", "actionpaste", "my action: paste", "https://example.com",
            "brb", "kind regards,\nElia",
        )) {
            assertNull("'$s' read as an action", EditorAction.of(s))
            assertFalse("'$s' read as a malformed action", EditorAction.isUnknownAction(s))
        }
    }

    @Test
    fun aMisspeltActionIsRecognizedAsOneRatherThanTyped() {
        // isUnknownAction swallows a typo in a chord expansion, so someone who meant to paste
        // never gets "action:pate" in the middle of their message.
        for (s in listOf("action:", "action:pate", "action:PASTE", "action:select all")) {
            assertNull(EditorAction.of(s))
            assertTrue("'$s' should be a malformed action", EditorAction.isUnknownAction(s))
        }
    }

    @Test
    fun theTwoShippedOutputsAreUnchanged() {
        // The comma key has stored these in preferences since the feature shipped;
        // renaming either would silently turn a configured key back into text.
        assertEquals("action:paste", EditorAction.PASTE.output)
        assertEquals("action:select_all", EditorAction.SELECT_ALL.output)
    }

    @Test
    fun everyOutputCarriesThePrefixAndIsDistinct() {
        val outputs = EditorAction.entries.map { it.output }
        assertEquals(outputs.size, outputs.toSet().size)
        for (o in outputs) {
            assertTrue("$o lacks the prefix", o.startsWith(EditorAction.PREFIX))
            // The prefix keeps an output from colliding with typeable text.
            assertTrue("$o is not longer than the prefix", o.length > EditorAction.PREFIX.length)
        }
    }
}
