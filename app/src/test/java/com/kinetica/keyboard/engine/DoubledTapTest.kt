package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One tap may stand for a doubled letter. When a tap consumed exactly one letter,
 * `ottima` tapped with a single `t` and `tutti` with two could not be read at all.
 */
class DoubledTapTest {

    private val g = TestData.qwertyGeometry()

    private fun asset(name: String): Path? = listOf(
        Paths.get("src/main/assets/dictionaries/$name"),
        Paths.get("app/src/main/assets/dictionaries/$name"),
    ).firstOrNull { Files.exists(it) }

    private fun italian(): WordPredictor {
        val p = asset("it_wordlist.txt")
        assumeTrue("it wordlist not found", p != null)
        val d = Files.newBufferedReader(p!!).use { DictionaryLoader.load(it) }
        return WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
    }

    private fun lead(p: WordPredictor, line: String): String? =
        p.decode(TraceReplay.tokens(line, g), emptyList()).firstOrNull()?.word

    @Test
    fun ottimaLeadsWithASingleTappedT() {
        // A captured buffer, rejected three times as `irina` and `prima`.
        val line = "decode in[it]: tap[o,RIGHT,t=38685832] tap[t,LEFT,t=38685958] " +
            "swipe[RIGHT,t=38686111..38686434,keys=i@0-106,k@106-148,m@148-323,arc=2.81,n=64/5ms] " +
            "tap[a,LEFT,t=38686358] ctx=[]"
        assertEquals("ottima", lead(italian(), line))
    }

    @Test
    fun tuttiLeadsWithTwoTappedTsForThree() {
        // A captured buffer, rejected as `tito` and `tiri`.
        val line = "decode in[it]: tap[t,LEFT,t=38714670] " +
            "swipe[RIGHT,t=38714921..38715248,keys=u@0-198,i@198-327,dwell=38714921-38715079/0.29kw/38n,arc=1.61,n=79/4ms] " +
            "tap[t,LEFT,t=38715081] ctx=[]"
        assertEquals("tutti", lead(italian(), line))
    }

    @Test
    fun aTapDoublesOnlyWhereTheWordDoes() {
        val lines = "otto\t1000\nobo\t10\n".reader().buffered().use { DictionaryLoader.load(it) }
        val p = WordPredictor(lines.trie, BigramTable.EMPTY, g, lines.forms)
        val line = "decode in[it]: tap[o,LEFT,t=1000] tap[t,LEFT,t=1200] tap[o,RIGHT,t=1400] ctx=[]"
        val out = p.decode(TraceReplay.tokens(line, g), emptyList()).map { it.word }
        assertEquals(listOf("otto"), out.filter { it == "otto" })
    }
}
