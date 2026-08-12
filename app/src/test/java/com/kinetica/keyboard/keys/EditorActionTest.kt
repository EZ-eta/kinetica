package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reserved-output parsing both the comma key and the chord shortcuts now
 * share. The defect this locks is the chord path inserting `action:paste` into
 * the user's document as literal text, because it never consulted the mapping.
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
        // Including text that merely mentions one, and text that would be a
        // plausible chord expansion.
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
        // The reason isUnknownAction exists: a typo in a chord expansion must be
        // swallowed, not inserted. Someone who meant to paste would otherwise get
        // "action:pate" in the middle of their message.
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
            // The prefix is what guarantees no collision with typeable text.
            assertTrue("$o is not longer than the prefix", o.length > EditorAction.PREFIX.length)
        }
    }
}
