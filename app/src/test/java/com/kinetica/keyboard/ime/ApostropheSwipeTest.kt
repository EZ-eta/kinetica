package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.models.StreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A swipe across the apostrophe key: the word before it is written, then `'`, then what lifted
 * meanwhile goes on as the next word.
 */
class ApostropheSwipeTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun tokensWaitForTheApostropheAndKeepTheirOrder() {
        val s = ApostropheSwipe()
        val a = TestData.tap('a', g, 10L, StreamId.LEFT)
        val b = TestData.tap('l', g, 20L, StreamId.RIGHT)
        assertFalse(s.hold(a))
        s.begin(generation = 7)
        assertTrue(s.pending)
        assertTrue(s.hold(a))
        assertTrue(s.hold(b))
        assertEquals(listOf(a, b), s.take())
        assertFalse(s.pending)
        assertFalse(s.hold(a))
        assertEquals(emptyList<Any>(), s.take())
    }

    @Test
    fun theWordIsDueAtItsDecodeOrALaterOne() {
        val s = ApostropheSwipe()
        assertFalse(s.due(9))
        s.begin(generation = 7)
        assertFalse(s.due(6))
        assertTrue(s.due(7))
        // A redecode of the same buffer, a dictionary reload: still the word before the key.
        assertTrue(s.due(8))
    }

    @Test
    fun anAbandonedWordWritesNoApostrophe() {
        val s = ApostropheSwipe()
        s.begin(generation = 3)
        s.hold(TestData.tap('a', g, 10L))
        s.drop()
        assertFalse(s.pending)
        assertFalse(s.due(3))
        assertEquals(emptyList<Any>(), s.take())
    }

    /** [declaration]'s body: up to the closing brace at [indent]. */
    private fun body(src: String, declaration: String, indent: String = "    "): String {
        val start = src.indexOf(declaration)
        assertTrue("$declaration not found", start >= 0)
        return src.substring(start, src.indexOf("\n$indent}\n", start))
    }

    @Test
    fun theServiceWritesTheWordThenTheApostropheThenTheRest() {
        // The service has no JVM reach: the order of each step is read in its source.
        val src = ImeSources.declaring("private fun settleApostropheSwipe(").text
        val settle = body(src, "private fun settleApostropheSwipe(")
        val steps = listOf(
            "if (!apostropheSwipe.pending) return",
            "mainHandler.removeCallbacks(apostropheSettle)",
            "val held = apostropheSwipe.take()",
            "elisionHead(wordState.lastCandidates.map { it.word }, heads)",
            "replaceTentative(displayWord(head))",
            "autospace.cancel()",
            "commit.finalizePendingWord()",
            "commitTracked(\"'\")",
            "updateAutoShift()",
            "for (t in held) onTokenFinalized(t)",
        )
        val at = steps.map { settle.indexOf(it) }
        assertTrue(steps.zip(at).toString(), at.all { it >= 0 } && at == at.sorted())
        // Only a swiped word's lead is swapped for an elision head: taps are what was typed.
        assertTrue(settle.contains("if (composer?.hasSwipeToken() == true && wordState.tentativeLength > 0)"))

        val token = body(src, "override fun onTokenFinalized(")
        assertTrue(token.indexOf("if (apostropheSwipe.hold(token))") in 0 until token.indexOf("autospace.takeShiftAt()"))
        val key = body(src, "override fun onKeyPressFeedback(", indent = "        ")
        assertTrue(key.indexOf("settleApostropheSwipe(\"key\")") in 0 until key.indexOf("keyDownAt[key.id]"))
        val cut = body(src, "override fun onApostropheSwipe(", indent = "        ")
        assertTrue(cut.indexOf("apostropheSwipe.begin(comp.currentGeneration)") in 0 until
            cut.indexOf("mainHandler.postDelayed(apostropheSettle, KineticaConstants.APOSTROPHE_CUT_WAIT_MS)"))
        assertTrue(src.contains("override fun mayCutAtApostrophe(): Boolean = !emojiSearch.active && composer != null"))
        val decoded = body(src, "override fun onCandidates(")
        assertTrue(decoded.indexOf("onCandidatesRanked(ranked, lead, literal)") in 0 until
            decoded.indexOf("if (apostropheSwipe.due(generation)) settleApostropheSwipe(\"decode\")"))

        val abandon = body(ImeSources.declaring("fun abandonWord()").text, "fun abandonWord()")
        assertTrue(abandon.contains("ime.apostropheSwipe.drop()"))
    }
}
