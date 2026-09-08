package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commit-time miss line, item 44's third instrument iteration.
 *
 * Format assertions, not decode assertions: the values it reports can only be judged
 * against a device, and this pins that the two marks mean what the attribution pass
 * counts. `!` is a letter contacted in order, `<` a letter contacted only too early, and
 * a bare token a letter no thumb ever touched.
 */
class CommitMissTest {

    private val g = TestData.qwertyGeometry()

    private fun line(word: String, drawn: String, src: String = "commit"): String? {
        val tokens: List<InputToken> = listOf(TestData.swipe(drawn, g, 0, 100L * drawn.length))
        val pattern = Matcher.buildPattern(tokens, g)
        assertNotNull("pattern for $drawn", pattern)
        return commitMissLine(word, tokens, pattern!!, g, src, -1L)
    }

    @Test
    fun aWordWhoseLettersWereAllTouchedInOrderReportsNoMiss() {
        val out = line("where", "where")
        assertNotNull(out)
        println("COMMITMISS $out")
        assertEquals("missing=0 in: $out", true, out!!.contains("missing=0"))
        assertTrue("back=0 in: $out", out.contains("back=0"))
        // One token per letter, every one contacted.
        assertEquals("five contacts in: $out", 5, out.count { it == '!' })
        assertTrue("no order break in: $out", !out.contains('<'))
    }

    @Test
    fun aLetterTheThumbNeverTouchedReportsHowCloseItCame() {
        // The same word drawn without its `h`: the letter is gone from the contact list
        // and the line has to say how far the path stayed from it, which is the number
        // item 44 exists to collect.
        val out = line("where", "were")
        assertNotNull(out)
        println("COMMITMISS $out")
        assertTrue("missing=1 in: $out", out!!.contains("missing=1"))
        val h = Regex("""h(\d+):([0-9.]+)@(\d+)""").find(out)
        assertNotNull("an h token in: $out", h)
        assertTrue("h carries no contact mark in: $out", !out.contains("!h"))
        assertTrue(
            "h reports a real distance, was ${h!!.groupValues[2]}",
            h.groupValues[2].toFloat() > 0.5f,
        )
    }

    @Test
    fun aLetterTouchedOnlyBeforeItsTurnIsMarkedRatherThanCredited() {
        // `praticamente`'s shape in two letters: the thumb crossed `t` and only then
        // reached `a`, so a word needing `a` before `t` has no reading, and the `t` must
        // not be credited from a contact that happened too early.
        val out = line("at", "ta")
        assertNotNull(out)
        println("COMMITMISS $out")
        assertTrue("back=1 in: $out", out!!.contains("back=1"))
        assertTrue("an order break in: $out", out.contains('<'))
        assertTrue("missing=0 in: $out", out.contains("missing=0"))
    }

    @Test
    fun theLineNamesItsSourceAndItsGap() {
        val tokens: List<InputToken> = listOf(TestData.swipe("where", g, 0, 500))
        val pattern = Matcher.buildPattern(tokens, g)!!
        val out = commitMissLine("where", tokens, pattern, g, "previous", 1456L)
        assertNotNull(out)
        assertTrue("src in: $out", out!!.contains("src=previous"))
        assertTrue("gap in: $out", out.contains("gap=1456"))
        // A commit line has no gap to report and must not invent one.
        val commit = commitMissLine("where", tokens, pattern, g, "commit", -1L)!!
        assertTrue("no gap in: $commit", !commit.contains("gap="))
    }

    @Test
    fun aContactedLetterCannotReportADistantPath() {
        // The defect its own first capture caught: the mark came from the contact
        // timeline and the distance from a position-constrained walk, so 146 of 1 506
        // contacted letters reported over 1.8 kw, which a key the finger was on cannot do.
        //
        // The fixture has to leave the forward-only walk a distant tail to measure, which
        // is how the capture produced `r2:5.01@28!`. A long left-to-right stroke, and a
        // word ending on the letter it STARTED at: the walk is down to the last few
        // samples over by `k` and reports six key widths, while a thumb was on the `a`.
        val tokens: List<InputToken> = listOf(TestData.swipe("asdfghjk", g, 0, 800))
        val pattern = Matcher.buildPattern(tokens, g)!!
        val out = commitMissLine("asdfghja", tokens, pattern, g, "commit", -1L)!!
        println("COMMITMISS $out")
        val bad = Regex("""[a-z]\d+:([0-9.]+)@\d+[!<=]""").findAll(out)
            .map { it.groupValues[1].toFloat() }
            .filter { it > KineticaConstants.R_INNER_KW }
            .toList()
        assertTrue("a contacted letter reporting a far path: $bad in $out", bad.isEmpty())
    }

    @Test
    fun aDoubledLetterSharesOneContactInsteadOfBreakingTheOrder() {
        // `orrendo` draws `rr` with one contact, so the second `r` has no later contact to
        // claim and is not an order violation. 78 of 431 order marks were this.
        val tokens: List<InputToken> = listOf(TestData.swipe("ore", g, 0, 300))
        val pattern = Matcher.buildPattern(tokens, g)!!
        val out = commitMissLine("orre", tokens, pattern, g, "commit", -1L)!!
        println("COMMITMISS $out")
        assertTrue("a doubled-letter mark in: $out", out.contains('='))
        assertTrue("back=0 in: $out", out.contains("back=0"))
    }

    @Test
    fun aSeededBufferIsFlaggedRatherThanCountedAsEvidence() {
        // reloadWordUnderCursor seeds anchors from committed text off one uptimeMillis
        // call, so they sit a millisecond apart where real taps are 100 to 600. Such a
        // line is not evidence about a thumb and says so.
        val seeded: List<InputToken> = "abc".mapIndexed { i, ch ->
            TapToken(StreamId.LEFT, ch - 'a', g.centerX(ch - 'a'), g.centerY(ch - 'a'), false, i.toLong(), i.toLong())
        }
        val pattern = Matcher.buildPattern(seeded, g)!!
        val out = commitMissLine("abc", seeded, pattern, g, "commit", -1L)!!
        println("COMMITMISS $out")
        assertTrue("note=seeded in: $out", out.contains("note=seeded"))

        // A real buffer at the same letters must not be flagged.
        val real: List<InputToken> = "abc".mapIndexed { i, ch ->
            TapToken(StreamId.LEFT, ch - 'a', g.centerX(ch - 'a'), g.centerY(ch - 'a'), false, 200L * i, 200L * i + 40)
        }
        val realOut = commitMissLine("abc", real, Matcher.buildPattern(real, g)!!, g, "commit", -1L)!!
        assertTrue("no note in: $realOut", !realOut.contains("note="))
    }

    @Test
    fun aCommitWhoseBufferSpellsNoneOfItIsFlaggedUnrelated() {
        // A picked suggestion or a completion commits a word the buffer never drew, and
        // that arrived as `let's` and `really` with every letter missing.
        val tokens: List<InputToken> = listOf(TestData.swipe("qwe", g, 0, 300))
        val out = commitMissLine("plonk", tokens, Matcher.buildPattern(tokens, g)!!, g, "commit", -1L)!!
        println("COMMITMISS $out")
        assertTrue("note=unrelated in: $out", out.contains("note=unrelated"))
    }

    @Test
    fun theSwipeTraceCarriesArcAndSampling() {
        // Both are new, and arc is the field the reconstruction destroys: a replayed
        // buffer is a clean polyline through the contacts, so its arc is shorter than
        // the thumb's, and arc is what decides minLetters and the length bands.
        val log = ArrayList<String>()
        DecodeTrace.sink = { log.add(it) }
        try {
            val d = DictionaryLoader.load("where 100\nhere 100\n".reader().buffered())
            WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
                .decode(listOf(TestData.swipe("where", g, 0, 500)), emptyList())
        } finally {
            DecodeTrace.sink = null
        }
        val input = log.first { it.startsWith("decode in") }
        assertTrue("arc in: $input", Regex("""arc=[0-9.]+""").containsMatchIn(input))
        assertTrue("sampling in: $input", Regex("""n=\d+/\d+ms""").containsMatchIn(input))
        // The fields must stay AFTER keys=, or TraceReplay stops parsing every fixture.
        assertTrue("arc after keys in: $input", input.indexOf("arc=") > input.indexOf("keys="))
        assertEquals(
            "the buffer still replays: $input",
            1,
            TraceReplay.tokens(input.substringAfter("decode in: "), g).size,
        )
    }
}
