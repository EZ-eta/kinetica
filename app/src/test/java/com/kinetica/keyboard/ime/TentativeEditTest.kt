package com.kinetica.keyboard.ime

import com.kinetica.keyboard.ime.TentativeEdit.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word in hand is rewritten by its changed tail only. Captured in a ProseMirror editor: a
 * merge that added the other thumb's letter deleted and rewrote the whole word 10 ms after the
 * swipe's write, and the editor's delete check then took one more letter, 8 times in 8.
 */
class TentativeEditTest {

    @Test
    fun theOtherThumbsLetterIsAPlainInsert() {
        // The captured words: the swipe wrote the head, the tap's merge added the last letter.
        assertEquals(Edit(0, "e"), TentativeEdit.between("th", "the"))
        assertEquals(Edit(0, "w"), TentativeEdit.between("ho", "how"))
        assertEquals(Edit(0, "t"), TentativeEdit.between("wha", "what"))
        assertEquals(Edit(0, "w"), TentativeEdit.between("no", "now"))
        assertEquals(Edit(0, "'t"), TentativeEdit.between("don", "don't"))
    }

    @Test
    fun aChangedLetterDeletesOnlyFromIt() {
        assertEquals(Edit(1, "he"), TentativeEdit.between("tg", "the"))
        assertEquals(Edit(1, "re"), TentativeEdit.between("then", "there"))
        assertEquals(Edit(1, ""), TentativeEdit.between("thee", "the"))
    }

    @Test
    fun theSameWordWritesNothing() {
        val edit = TentativeEdit.between("the", "the")
        assertEquals(Edit(0, ""), edit)
        assertTrue(edit.writesNothing)
    }

    @Test
    fun aRecaseRewritesFromTheFirstChangedLetter() {
        assertEquals(Edit(3, "The"), TentativeEdit.between("the", "The"))
        assertEquals(Edit(2, "HE"), TentativeEdit.between("the", "tHE"))
    }

    @Test
    fun anEmojiIsNeverCutInHalf() {
        // Two emoji sharing a high surrogate: the shared half must go with its pair.
        val grin = "😀"
        val smile = "😄"
        assertEquals(Edit(2, smile), TentativeEdit.between(grin, smile))
        assertEquals(Edit(2, smile), TentativeEdit.between("ab$grin", "ab$smile"))
    }

    @Test
    fun anEmptyWordIsAPlainInsert() {
        assertEquals(Edit(0, "th"), TentativeEdit.between("", "th"))
    }

    @Test
    fun replaceTentativeGoesThroughTheTail() {
        // The IME path needs a live editor, so the guard is a source scan ([ImeSources]): a
        // fail-first check here needs `--rerun-tasks`.
        val src = ImeSources.declaring("internal fun replaceTentative(").text
        val at = src.indexOf("internal fun replaceTentative(")
        val body = src.substring(at, src.indexOf("\n    }", at))
        assertTrue("replaceTentative writes the whole word", body.contains("TentativeEdit.between("))
        assertTrue("replaceTentative writes when nothing changed", body.contains("writesNothing"))
    }
}
