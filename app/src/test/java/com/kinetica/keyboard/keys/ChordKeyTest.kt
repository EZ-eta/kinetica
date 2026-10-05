package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A chord named by its trigger and its key, stored in a form older builds skip safely. */
class ChordKeyTest {

    @Test
    fun everyChordAnEarlierBuildWroteIsAQuestionMarkChord() {
        assertEquals(ChordKey(ChordTrigger.MODE, 'l'), ChordKey.decode("l"))
        assertEquals(ChordKey(ChordTrigger.MODE, 'й'), ChordKey.decode("Й"))
        assertEquals(ChordKey(ChordTrigger.MODE, ':'), ChordKey.decode(":"))
    }

    @Test
    fun aSpacebarChordRoundTrips() {
        for (k in listOf(ChordKey(ChordTrigger.SPACE, 't'), ChordKey(ChordTrigger.SPACE, ':'), ChordKey(ChordTrigger.MODE, '1'))) {
            assertEquals(k, ChordKey.decode(k.encode()))
        }
        assertEquals("space:t", ChordKey(ChordTrigger.SPACE, 't').encode())
        assertEquals("t", ChordKey(ChordTrigger.MODE, 't').encode())
    }

    @Test
    fun aSpacebarChordIsNoChordToAnOlderRead() {
        // Earlier builds stored one character, so they skip a spacebar row, not misread it.
        assertNull(ChordKey(ChordTrigger.SPACE, 't').encode().singleOrNull())
    }

    @Test
    fun anythingElseIsNoChord() {
        for (s in listOf("", "ab", "space:", "space:ab", "spac:t", "mode:t")) assertNull(s, ChordKey.decode(s))
    }
}
