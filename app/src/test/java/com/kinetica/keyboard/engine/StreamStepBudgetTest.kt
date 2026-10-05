package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The stream pass stops after [KineticaConstants.STREAM_SEARCH_STEP_BUDGET] descend steps.
 * Its cost is trie walking, which the emit and attempt budgets never see.
 */
class StreamStepBudgetTest {

    @Test
    fun theHeaviestCapturedBufferHitsTheBudget() {
        val g = TestData.qwertyGeometry()
        val p = listOf(
            Paths.get("src/main/assets/dictionaries/it_wordlist.txt"),
            Paths.get("app/src/main/assets/dictionaries/it_wordlist.txt"),
        ).firstOrNull { Files.exists(it) }
        assumeTrue("it wordlist not found", p != null)
        val d = Files.newBufferedReader(p!!).use { DictionaryLoader.load(it) }
        val predictor = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        // A captured buffer of two long swipes, the worst JVM decode in the corpus before the budget.
        val line = "decode in[it]: " +
            "swipe[LEFT,t=11952904..11954936,keys=x@0-71,f@71-99,g@99-131,y@131-153,u@153-204,j@204-317,h@317-361,v@361-437,c@437-525,v@525-547,h@547-585,j@585-656,k@656-865,i@865-887,u@887-920,h@920-1013,b@1013-1172,n@1172-1221,j@1221-1461,k@1461-1636,m@1636-1658,n@1658-1713,b@1713-1806,h@1806-1888,u@1888-1909,i@1909-1964,k@1964-2009,l@2009-2032,arc=29.85,n=492/4ms]" +
            " swipe[RIGHT,t=11953720..11954888,keys=f@0-104,r@104-142,e@142-279,s@279-416,x@416-481,f@481-601,d@601-642,s@642-738,a@738-804,z@804-875,x@875-926,c@926-979,f@979-1061,d@1061-1110,e@1110-1126,s@1126-1142,a@1142-1168,arc=17.46,n=284/4ms] ctx=[]"
        val lines = ArrayList<String>()
        DecodeTrace.sink = { lines.add(it) }
        try {
            predictor.decode(TraceReplay.tokens(line, g), emptyList())
        } finally {
            DecodeTrace.sink = null
        }
        val stream = lines.firstOrNull { it.startsWith("stream[") }
        assertTrue("the stream pass must run: $lines", stream != null)
        val stops = Regex("stops=(\\d+)").find(stream!!)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue("the budget must stop this pass: $stream", stops > 0)
    }
}
