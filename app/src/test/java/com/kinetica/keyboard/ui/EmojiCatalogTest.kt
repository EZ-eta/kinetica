package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The generated emoji set (`tools/generate_emoji.py`) and the parser the pane and the search
 * share. ZWJ sequences are carried since 3.0: `Paint.hasGlyph` hides one the font cannot draw as
 * a single glyph, so the reason for leaving them out is gone.
 */
class EmojiCatalogTest {

    private val catalog get() = EmojiAssetFiles.catalog

    @Test
    fun theSetIsEveryUnicodeEmojiWithoutATone() {
        val n = catalog.entries.size
        assertTrue("expected the full set, found $n", n >= 1800)
        val dupes = catalog.entries.groupBy { it.ch }.filterValues { it.size > 1 }.keys
        assertTrue("duplicated emoji: $dupes", dupes.isEmpty())
        val toned = catalog.entries.filter { e -> e.ch.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } }
        assertTrue("skin tones are not carried: ${toned.map { it.ch }}", toned.isEmpty())
        assertTrue("every emoji has a name", catalog.entries.all { it.name.isNotBlank() })
    }

    @Test
    fun zwjSequencesAndFlagsAreCarried() {
        val chars = catalog.entries.map { it.ch }.toSet()
        for (ch in listOf("❤️‍🔥", "🧑‍💻", "🏳️‍🌈", "🇮🇹", "🇺🇦")) {
            assertTrue("$ch is missing", chars.contains(ch))
        }
    }

    @Test
    fun theNineTabsAreThereInUnicodeOrder() {
        assertEquals(
            listOf("Smileys", "People", "Animals", "Food", "Travel", "Activities", "Objects", "Symbols", "Flags"),
            catalog.groups.map { it.name },
        )
        assertTrue("every tab has an icon", catalog.groups.all { it.icon.isNotEmpty() })
    }

    @Test
    fun everyTabIconIsAnEmojiOfItsOwnPanel() {
        // Hearts are in Smileys & Emotion since Unicode 12: a heart on Symbols named a panel
        // that held none.
        val strange = catalog.groups.filter { g -> g.entries.none { it.ch == g.icon } }
        assertTrue("tab icons not in their own panel: ${strange.map { "${it.name} ${it.icon}" }}", strange.isEmpty())
    }

    @Test
    fun noEmojiOfTheFirstSetIsLost() {
        val direct = Paths.get("../tools/emoji_extra_en.tsv")
        val p = if (Files.exists(direct)) direct else Paths.get("tools/emoji_extra_en.tsv")
        assumeTrue("emoji_extra_en.tsv not found", Files.exists(p))
        val have = catalog.entries.map { it.ch.replace("️", "") }.toSet()
        val lost = Files.readAllLines(p).filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBefore('\t').replace("️", "") }
            .filter { it !in have }
        assertTrue("the first set's emoji must survive: $lost", lost.isEmpty())
    }

    @Test
    fun everyLanguageHasALineOfWordsPerEmoji() {
        val total = EmojiAssetFiles.lines("emoji.tsv").count { it.isNotEmpty() && !it.startsWith("@") }
        for (lang in EmojiAssetFiles.LANGS) {
            val words = EmojiAssetFiles.words(lang)
            assertEquals("kw_$lang.tsv must align with emoji.tsv", total, words.size)
            val bare = words.count { it.name.isEmpty() && it.other.isEmpty() }
            assertTrue("$lang: $bare emoji without words", bare <= 2)
        }
    }

    @Test
    fun anUndrawableEmojiAndAnEmptyTabAreDropped() {
        val lines = sequenceOf("@A\t😀", "😀\tgrinning face", "🫨\tshaking face", "@B\t🫨", "🫨\tagain")
        val c = EmojiCatalog.parse(lines) { it != "🫨" }
        assertEquals(listOf("A"), c.groups.map { it.name })
        assertEquals(listOf("😀"), c.entries.map { it.ch })
        // Indexes count every emoji line, drawn or not, so the words stay aligned.
        val after = EmojiCatalog.parse(sequenceOf("@A\t😀", "🫨\tshaking face", "🙂\tslightly smiling")) { it != "🫨" }
        assertEquals(listOf("🙂"), after.entries.map { it.ch })
        assertEquals("the emoji after an undrawable one keeps its line", 1, after.entries.single().index)
        assertEquals(listOf(0, 1, 2), EmojiCatalog.parse(lines).entries.map { it.index })
    }

    @Test
    fun wordsAreFoldedAtLoad() {
        val w = EmojiCatalog.parseWords(sequenceOf("\tCaffè\tbevanda calda")).single()
        assertEquals(listOf("caffe"), w.name)
        assertEquals(listOf("bevanda", "calda"), w.other)
        assertTrue(w.hand.isEmpty())
    }
}
