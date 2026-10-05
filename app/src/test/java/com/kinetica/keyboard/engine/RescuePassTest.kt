package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The rescue pass: when the lead fits past a whole key or nothing was found, one
 * more pass reads a first letter off the start radius and a close through any letter with a
 * pass, each at a score charge. Rejected commits on the captured corpus fit at a median 0.59
 * against 0.25 for accepted ones.
 */
class RescuePassTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String): Path? = listOf(
        Paths.get("src/main/assets/dictionaries/$name"),
        Paths.get("app/src/main/assets/dictionaries/$name"),
    ).firstOrNull { Files.exists(it) }

    private fun predictor(lang: String): WordPredictor {
        val p = asset("${lang}_wordlist.txt")
        assumeTrue("$lang wordlist not found", p != null)
        val d = Files.newBufferedReader(p!!).use { DictionaryLoader.load(it) }
        return WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = lang)
    }

    private fun decode(p: WordPredictor, line: String): List<WordCandidate> =
        p.decode(TraceReplay.tokens(line, g), emptyList())

    @Test
    fun believeLeadsOverAPoorFit() {
        // A captured two-thumb `believe` that led as `verge`; another capture lost the same
        // word as `liver`.
        val line = "decode in[en]: swipe[RIGHT,t=38834141..38834917,keys=b@0-165,n@165-197,k@197-235," +
            "l@235-339,k@339-427,i@427-630,j@630-701,h@701-776,arc=6.64,n=170/4ms] tap[e,LEFT,t=38834219] " +
            "swipe[LEFT,t=38834722..38835316,keys=e@0-164,r@164-181,f@181-222,v@222-370,c@370-381,f@381-430," +
            "r@430-583,e@583-594,arc=9.53,n=141/4ms] ctx=[]"
        assertEquals("believe", decode(predictor("en"), line).firstOrNull()?.word)
    }

    @Test
    fun comunqueLeadsOverAPoorFit() {
        // A buffer that led as `couwenberg`.
        val line = "decode in[it]: tap[c,LEFT,t=38005068] swipe[RIGHT,t=38005135..38005981,keys=o@0-79," +
            "k@79-113,m@113-255,j@255-305,u@305-442,j@442-552,n@552-639,j@639-689,h@689-846] " +
            "swipe[LEFT,t=38005561..38006083,keys=q@0-273,w@273-345,e@345-522," +
            "dwell=38005561-38005802/0.26kw/55n] ctx=[]"
        assertEquals("comunque", decode(predictor("it"), line).firstOrNull()?.word)
    }

    @Test
    fun anEmptyDecodeIsRescuedAndMarkedAsSuch() {
        // Two buffers that decoded to nothing.
        val quindi = "decode in[it]: tap[q,LEFT,t=30696265] swipe[RIGHT,t=30696358..30697012,keys=h@0-71," +
            "u@71-93,i@93-229,k@229-246,j@246-295,n@295-448,j@448-508,i@508-654] tap[d,LEFT,t=30696737] ctx=[]"
        val proverbio = "decode in[it]: swipe[RIGHT,t=2154790..2155105,keys=p@0-127,o@127-240,i@240-306," +
            "j@306-315] tap[r,LEFT,t=2154878] tap[v,LEFT,t=2155276] tap[e,RIGHT,t=2155349] " +
            "tap[r,LEFT,t=2155555] swipe[RIGHT,t=2155606..2156084,keys=b@0-141,j@141-239,i@239-385,o@385-478] ctx=[]"
        val it = predictor("it")
        for ((word, line) in listOf("quindi" to quindi, "proverbio" to proverbio)) {
            val out = decode(it, line)
            assertEquals(word, out.firstOrNull()?.word)
            assertEquals(word, WordCandidate.Source.RESCUE, out.first().source)
        }
    }

    private fun rescueLine(p: WordPredictor, line: String): String {
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            decode(p, line)
        } finally {
            DecodeTrace.sink = null
        }
        return lines.firstOrNull { it.startsWith("rescue ") } ?: error("the rescue must run: $lines")
    }

    private fun stops(summary: String): Int =
        Regex("stops=(\\d+)").find(summary)?.groupValues?.get(1)?.toInt() ?: 0

    @Test
    fun aHeavyRescueStopsAtItsBudgets() {
        // Captured two-thumb buffers where the rescue was the phone's slowest decode.
        // The first spent 531 scoring attempts before the cap, the second walked past 15 000 steps.
        val manyAttempts = "decode in[en]: swipe[LEFT,t=45599770..45600754,keys=c@0-115,f@115-159,t@159-230," +
            "r@230-290,e@290-452,r@452-640,f@640-782,g@782-864,t@864-984,arc=11.34,n=221/4ms] " +
            "tap[o,RIGHT,t=45599810] ctx=[]"
        val manySteps = "decode in[it]: swipe[RIGHT,t=45714837..45715432,keys=k@0-125,j@125-207,u@207-240," +
            "y@240-426,g@426-464,v@464-513,b@513-595,arc=6.26,n=139/4ms] " +
            "swipe[LEFT,t=45715417..45715765,keys=e@0-189,d@189-348,arc=2.4,n=76/4ms] ctx=[]"
        val a = rescueLine(predictor("en"), manyAttempts)
        assertTrue("the attempt cap must stop this pass: $a", stops(a) > 0)
        val b = rescueLine(predictor("it"), manySteps)
        assertTrue("the step budget must stop this pass: $b", stops(b) > 0)
    }

    private fun cand(word: String, source: WordCandidate.Source) =
        WordCandidate(word, 0.1f, 1.2f, 0.5f, 1f, 0, source, "it")

    @Test
    fun aListOnlyTheRescueFoundIsPickOnly() {
        val rescued = listOf(cand("quindi", WordCandidate.Source.RESCUE), cand("quando", WordCandidate.Source.RESCUE))
        val held = rescueHeld(WordComposer.Merged(rescued, rescued.first(), 0, "single"))
        assertNull(held.tentative)
        assertEquals(rescued, held.candidates)
        // One reading the ordinary passes found is enough for the lead to commit as before.
        val mixed = rescued + cand("quinto", WordCandidate.Source.MERGED)
        val m = WordComposer.Merged(mixed, mixed.first(), 0, "single")
        assertSame(m, rescueHeld(m))
    }
}
