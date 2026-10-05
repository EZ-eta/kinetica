package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The field's commits and the words their bars offered, for an offer inside one of them. */
class CommitHistoryTest {

    private fun history() = CommitHistory().apply {
        // "plus key ": `key` swiped, its bar showing key, let, meet, met, jet.
        record(0, "plus", emptyList(), emptyMap())
        record(5, "key", listOf("let", "meet", "met", "jet"), mapOf("let" to "en"))
    }

    @Test
    fun aWordWrittenHereKeepsItsBar() {
        val r = history().at(5, "key")!!
        assertEquals(listOf("let", "meet", "met", "jet"), r.alternatives)
        assertEquals("en", r.languages["let"])
        assertNull(history().at(6, "key"))
        assertNull(history().at(5, "kez"))
    }

    @Test
    fun aPickedAlternativeStillFindsItsBar() {
        val h = history()
        h.replaced(5, "key", "let")
        val r = h.at(5, "let")!!
        assertEquals("let", r.word)
        assertEquals(listOf("key", "meet", "met", "jet"), r.alternatives)
    }

    @Test
    fun typingEarlierMovesTheRecordsAfterIt() {
        val h = history()
        h.onEdit(at = 0, deleted = 0, inserted = 3) // "so " written before `plus`
        assertEquals("key", h.at(8, "key")!!.word)
        assertNull(h.at(5, "key"))
    }

    @Test
    fun anEditIntoAWordDropsItsRecord() {
        val h = history()
        h.onEdit(at = 8, deleted = 1, inserted = 0) // backspace into `key`
        assertNull(h.at(5, "key"))
        assertEquals("plus", h.at(0, "plus")!!.word)
    }

    @Test
    fun aLongerReplacementMovesWhatFollows() {
        val h = history()
        h.record(9, "now", emptyList(), emptyMap())
        h.replaced(5, "key", "meet")
        assertEquals("now", h.at(10, "now")!!.word)
        h.clear()
        assertEquals(0, h.size)
    }
}
