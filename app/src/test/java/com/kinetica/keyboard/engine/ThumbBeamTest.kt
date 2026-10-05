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
}
