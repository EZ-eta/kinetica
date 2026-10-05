package com.kinetica.keyboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream pass's own rules, run alone on a small dictionary so no other pass can find the
 * same words first.
 *
 * Fixtures are replay lines with hand-set contact times. `world` is spelled w(L) o(R) r(L)
 * l(R) d(L): the right thumb draws `o` then `l` and lifts, and the left thumb, starting after
 * it, draws `r` then `d`. So `r` must be read before `l` although it happened after it: a
 * hand-back, whose size is set by how long the left thumb holds `r`.
 */
class StreamSearchTest {

    private val g = TestData.qwertyGeometry()

    private fun predictor(vararg words: String): WordPredictor {
        val lines = words.joinToString("\n") { "$it\t1000" } + "\n"
        val d = lines.reader().buffered().use { DictionaryLoader.load(it) }
        return WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
    }

    /** `world` with the left thumb holding `r` until [rExitMs] after its own start. */
    private fun world(rExitMs: Int): String {
        val dEnd = rExitMs + 150
        return "decode in[en]: tap[w,LEFT,t=1000] " +
            "swipe[RIGHT,t=1050..1250,keys=o@0-70,l@70-200] " +
            "swipe[LEFT,t=1300..${1300 + dEnd},keys=r@0-$rExitMs,d@$rExitMs-$dEnd] ctx=[]"
    }

    private fun words(line: String, p: WordPredictor): List<String> =
        p.streamPassOnly(TraceReplay.tokens(line, g)).map { it.word }

    @Test
    fun aHandBackInsideTheAllowanceIsRead() {
        // `r` ends 100 ms after `l` does: a step back well inside HANDOVER_ALLOWANCE_MS.
        val back = 1300 + 50 - 1250
        assertTrue(back < KineticaConstants.HANDOVER_ALLOWANCE_MS)
        val out = words(world(rExitMs = 50), predictor("world", "word"))
        assertTrue("world must be read with a hand-back of ${back}ms; got $out", out.contains("world"))
    }

    @Test
    fun aHandBackBeyondTheAllowanceIsRefused() {
        // `r` ends 350 ms after `l` does.
        val back = 1300 + 300 - 1250
        assertTrue(back > KineticaConstants.HANDOVER_ALLOWANCE_MS)
        val out = words(world(rExitMs = 300), predictor("world", "word"))
        assertFalse("a ${back}ms hand-back must be refused; got $out", out.contains("world"))
    }

    @Test
    fun everyTokenIsConsumed() {
        // `wor` spells a prefix of the buffer and leaves the left thumb's `d` unread.
        val out = words(world(rExitMs = 50), predictor("world", "wor"))
        assertFalse("a reading must consume every token; got $out", out.contains("wor"))
    }

    @Test
    fun aSingleThumbBufferNeverRunsThePass() {
        val line = "decode in[en]: swipe[RIGHT,t=1000..1400,keys=w@0-80,o@80-160,r@160-240,l@240-320,d@320-400] ctx=[]"
        assertTrue(words(line, predictor("world")).isEmpty())
    }
}
