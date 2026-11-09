package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji search ranking, on small fixtures and on the real assets. */
class EmojiSearchTest {

    private fun fixture(vararg rows: Pair<String, String>): Pair<EmojiCatalog, List<EmojiCatalog.Words>> {
        val catalog = EmojiCatalog.parse((listOf("@G\t😀") + rows.map { "${it.first}\tx" }).asSequence())
        val words = EmojiCatalog.parseWords(rows.map { it.second }.asSequence())
        return catalog to words
    }

    private fun rank(
        query: String,
        f: Pair<EmojiCatalog, List<EmojiCatalog.Words>>,
        uses: Map<String, Int> = emptyMap(),
    ): List<String> = EmojiSearch.rank(query, f.first.entries, listOf(f.second), uses).map { it.ch }

    @Test
    fun aWholeWordBeatsAPrefix() {
        val f = fixture("🍭" to "\tlollipop\t", "😂" to "\ttears\tlol")
        assertEquals(listOf("😂", "🍭"), rank("lol", f))
    }

    @Test
    fun handBeatsNameBeatsKeyword() {
        val f = fixture("a" to "\t\tsun", "b" to "\tsun\t", "c" to "sun\t\t")
        assertEquals(listOf("c", "b", "a"), rank("sun", f))
    }

    @Test
    fun everyQueryWordMustMatch() {
        val f = fixture("❤️" to "\tred heart\t", "🫀" to "\tanatomical heart\torgan", "🔴" to "\tred circle\t")
        assertEquals(listOf("❤️"), rank("red heart", f))
    }

    @Test
    fun useCountThenShorterNameBreakATie() {
        val f = fixture("💘" to "\theart with arrow\t", "💔" to "\tbroken heart\t", "❤️" to "\tred heart\t")
        assertEquals(listOf("💔", "❤️", "💘"), rank("heart", f))
        assertEquals(listOf("❤️", "💔", "💘"), rank("heart", f, mapOf("❤️" to 3)))
    }

    @Test
    fun accentsAndCaseFold() {
        val f = fixture("☕" to "\tcaffè\t")
        assertEquals(listOf("☕"), rank("CAFFE", f))
        assertEquals(listOf("☕"), rank("caffè", f))
    }

    @Test
    fun anEmptyOrUnmatchedQueryFindsNothing() {
        val f = fixture("☕" to "\tcoffee\t")
        assertTrue(rank("", f).isEmpty())
        assertTrue(rank("  ,", f).isEmpty())
        assertTrue(rank("zzqx", f).isEmpty())
    }

    @Test
    fun theRowIsCapped() {
        val rows = (0 until 40).map { "e$it" to "\tsame\t" }.toTypedArray()
        assertEquals(EmojiSearch.RESULTS, rank("same", fixture(*rows)).size)
    }

    // The real assets. The first screen of the row shows eight cells.

    private fun firstScreen(query: String, vararg langs: String) = EmojiAssetFiles.top(query, *langs, n = 8)

    @Test
    fun theRedHeartIsOnTheFirstScreenInEveryTestedLanguage() {
        for ((q, lang) in listOf("heart" to "en", "cuore" to "it", "herz" to "de", "serce" to "pl", "сердце" to "ru")) {
            val top = firstScreen(q, lang, "en")
            assertTrue("$q ($lang): $top", top.contains("❤️"))
        }
        assertEquals("❤️", EmojiAssetFiles.top("red heart", "en").first())
    }

    @Test
    fun theHandWrittenWordsSurvive() {
        val top = EmojiAssetFiles.top("lol", "en")
        assertTrue("lol: $top", top.indexOf("😂") in 0..7)
        assertTrue("the lollipop only after the laughing faces: $top", top.indexOf("🍭") > top.indexOf("😂"))
    }

    @Test
    fun commonWordsLeadWithTheirEmoji() {
        assertEquals("☕", EmojiAssetFiles.top("caffe", "it", "en").first())
        assertEquals("🔥", EmojiAssetFiles.top("fire", "en").first())
        assertEquals("🔥", EmojiAssetFiles.top("fuoco", "it", "en").first())
        assertEquals("🍕", EmojiAssetFiles.top("pizza", "en").first())
        assertEquals("🇮🇹", EmojiAssetFiles.top("italy", "en").first())
        assertEquals("👍", EmojiAssetFiles.top("thumbs", "en").first())
        assertTrue(EmojiAssetFiles.top("zzqx", "en", "it").isEmpty())
    }

    @Test
    fun romanianFindsItsOwnWords() {
        // Romanian has its own board; without kw_ro.tsv its search fell back to English words only.
        assertTrue(firstScreen("inima", "ro", "en").contains("❤️"))
        assertEquals("🔥", EmojiAssetFiles.top("foc", "ro", "en").first())
        assertTrue(firstScreen("cafea", "ro", "en").contains("☕"))
    }
}
