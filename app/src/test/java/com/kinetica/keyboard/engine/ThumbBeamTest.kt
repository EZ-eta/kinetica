package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The thumb-cursor beam: the buffer read with one cursor per thumb, letters handed
 * over between any two, a swipe scored against the ideal path through its own letters. Each
 * fixture is a word the DFS passes alone lose, from recorded typing.
 */
class ThumbBeamTest {

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

    private fun lead(lang: String, line: String): String? =
        predictor(lang).decode(TraceReplay.tokens("decode in[$lang]: $line ctx=[]", g), emptyList()).firstOrNull()?.word

    @Test
    fun believeWithBothEsTappedLeads() {
        // A captured buffer; the passes lead `belive`.
        assertEquals(
            "believe",
            lead(
                "en",
                "swipe[RIGHT,t=38837400..38838225,keys=b@0-176,j@176-199,k@199-262,l@262-339,k@339-394," +
                    "i@394-418,u@418-514,h@514-647,g@647-705,v@705-825,arc=8.41,n=188/4ms] " +
                    "tap[e,LEFT,t=38837527] tap[e,LEFT,t=38837970]",
            ),
        )
    }

    @Test
    fun worldAcrossTwoSwipesAndATapLeads() {
        // A hard word for the passes, which lead `worked`.
        assertEquals(
            "world",
            lead(
                "en",
                "tap[w,LEFT,t=21963190] swipe[RIGHT,t=21963365..21963796,keys=o@0-177,k@177-431] " +
                    "swipe[LEFT,t=21963476..21963792,keys=r@0-177,d@177-316]",
            ),
        )
    }

    @Test
    fun happensInThreeInterleavedSwipesLeads() {
        // The passes lead `happiness`.
        assertEquals(
            "happens",
            lead(
                "en",
                "swipe[RIGHT,t=9684581..9685068,keys=h@0-137,g@137-208,f@208-301,d@301-350,s@350-459,a@459-487] " +
                    "swipe[LEFT,t=9685015..9685522,keys=p@0-102,o@102-130,k@130-234,j@234-288,n@288-507] " +
                    "swipe[RIGHT,t=9685370..9685718,keys=e@0-222,s@222-348]",
            ),
        )
    }

    @Test
    fun probabilmenteFromTwoSwipesAndTwoTapsLeads() {
        // A captured buffer; the passes lead `provarne`.
        assertEquals(
            "probabilmente",
            lead(
                "it",
                "swipe[RIGHT,t=36822395..36823504,keys=p@0-126,o@126-174,i@174-185,k@185-202,j@202-251," +
                    "b@251-460,h@460-471,j@471-513,i@513-677,o@677-732,l@732-792,m@792-946,n@946-1109," +
                    "arc=13.57,n=261/4ms] tap[r,LEFT,t=36822440] tap[a,LEFT,t=36822828] " +
                    "swipe[LEFT,t=36823276..36823858,keys=e@0-180,r@180-234,t@234-414,r@414-485,e@485-582," +
                    "arc=4.77,n=134/4ms]",
            ),
        )
    }

    @Test
    fun aBeamWordBelowThePassesLeadTakesAFreeSlot() {
        // A hand-checked buffer; the passes lead `langford` and never produce `landscape`.
        val words = predictor("en").decode(
            TraceReplay.tokens(
                "decode in[en]: tap[l,RIGHT,t=38587511] tap[a,LEFT,t=38587534] tap[n,RIGHT,t=38587767] " +
                    "swipe[LEFT,t=38587785..38588515,keys=s@0-196,d@196-250,c@250-475,d@475-602,r@602-726," +
                    "e@726-730,dwell=38588054-38588216/0.26kw/32n,arc=4.78,n=149/4ms] tap[o,RIGHT,t=38588305] ctx=[]",
                g,
            ),
            emptyList(),
        ).map { it.word }
        assertTrue("landscape must be offered: $words", "landscape" in words)
    }

    @Test
    fun theBeamReportsItsWorkOnTheTrace() {
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            lead("en", "tap[w,LEFT,t=21963190] swipe[RIGHT,t=21963365..21963796,keys=o@0-177,k@177-431] " +
                "swipe[LEFT,t=21963476..21963792,keys=r@0-177,d@177-316]")
        } finally {
            DecodeTrace.sink = null
        }
        val beam = lines.firstOrNull { it.startsWith("beam[") }
        assertTrue("the beam must run and report: $lines", beam != null && "cands=" in beam)
    }

    /**
     * The census's diagnosis: the beam held to one word reads every way of sharing its letters
     * between the thumbs, scores each, and names the letter where none goes on. A recorded `who` is
     * read as tapped, `w` on the left thumb and `ho` on the right stroke; `whoa` stops at its `a`,
     * with no item left on either thumb and the stroke nowhere near it.
     */
    @Test
    fun theBeamHeldToOneWordReadsItsWaysAndWhereTheyStop() {
        val p = predictor("en")
        val tokens = TraceReplay.tokens(
            "decode in[en]: tap[w,LEFT,t=18395486] " +
                "swipe[RIGHT,t=18395591..18395868,keys=h@0-64,j@64-108,k@108-146,o@146-277,arc=3.09,n=63/4ms] ctx=[]",
            g,
        )
        val seq = MergeAlternatives.sequences(tokens, DtwMatcher()).first()
        val items = Matcher.buildPattern(seq, g)!!
        fun alone(word: String): ThumbBeam {
            val nodes = IntArray(word.length) { p.trie.prefixNode(word.substring(0, it + 1)) }
            val b = ThumbBeam(p, g, seq, items, CandidateHeap(KineticaConstants.TOP_K), -1, null)
            b.watch = nodes
            b.only = nodes
            b.width = 20_000
            b.maxAttempts = Int.MAX_VALUE
            b.run()
            return b
        }
        val who = alone("who")
        val best = who.readings.maxByOrNull { it.score }
        assertTrue("who must be read: ${who.watchAt}", best != null && best.score > 0f)
        assertEquals("w on the left thumb, h and o on the right", listOf(0, 1, 1), best!!.stream.toList())
        val whoa = alone("whoa")
        assertTrue("whoa cannot be spelled here", whoa.readings.isEmpty())
        assertEquals(3, whoa.reached)
        val at = whoa.watchAt[4].orEmpty()
        assertTrue("its a: no item left, and off the stroke: $at", "open.none" in at && "cont.nopass" in at)
    }

    /**
     * A recorded `who`, a `w` tap then the right thumb's stroke. Read as `how`, the tap steps
     * back about 300 ms: past the allowance, so charged, and scored below `who`. Moved earlier,
     * the tap is still opened for `how` at about 600 ms back and refused at about 1 000, past
     * HANDOVER_CHARGE_MAX_MS.
     */
    @Test
    fun aHandOverIsChargedUpToItsCapAndRefusedPastIt() {
        val p = predictor("en")
        fun beam(shift: Long, watched: String?): Pair<List<String>, Map<String, Int>> {
            val tokens = TraceReplay.tokens(
                "decode in[en]: tap[w,LEFT,t=${18395486 - shift}] " +
                    "swipe[RIGHT,t=18395591..18395868,keys=h@0-64,j@64-108,k@108-146,o@146-277,arc=3.09,n=63/4ms] ctx=[]",
                g,
            )
            val seq = MergeAlternatives.sequences(tokens, DtwMatcher()).first()
            val items = Matcher.buildPattern(seq, g)
            assertTrue(ThumbBeam.applies(seq, items))
            val heap = CandidateHeap(KineticaConstants.TOP_K)
            val b = ThumbBeam(p, g, seq, items!!, heap, -1, null)
            if (watched != null) b.watch = IntArray(watched.length) { p.trie.prefixNode(watched.substring(0, it + 1)) }
            b.run()
            return heap.sortedByScoreDesc().map { it.word } to b.watchWhy
        }
        val near = beam(0, null).first
        assertTrue("how must be read with its tap 300 ms back: $near", "how" in near)
        assertEquals("who must lead its own buffer: $near", "who", near.first())
        val inside = beam(300, "how").second
        assertTrue("the tap must open for how about 600 ms back: $inside", "open.taptime" !in inside)
        val past = beam(700, "how").second
        assertTrue("the tap must be refused for how about 1 000 ms back: $past", "open.taptime" in past)
    }
}
