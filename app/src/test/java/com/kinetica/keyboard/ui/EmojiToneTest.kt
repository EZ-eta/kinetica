package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The skin tone setting: the toned forms the asset carries, the swap, its stored names and the ranking. */
class EmojiToneTest {

    private val catalog get() = EmojiAssetFiles.catalog

    private fun entry(ch: String): EmojiCatalog.Entry = catalog.entries.single { it.ch == ch }

    @Test
    fun aHandTakesEachToneAndNoneLeavesItYellow() {
        val wave = entry("👋")
        assertEquals("👋", EmojiTone.apply(wave, EmojiTone.NONE))
        assertEquals(listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿"), (1..EmojiTone.DARKEST).map { EmojiTone.apply(wave, it) })
    }

    @Test
    fun anEmojiWithNoToneIsUnchanged() {
        for (ch in listOf("😀", "❤️", "🐶", "🇮🇹", "🧑‍🧑‍🧒")) {
            val e = entry(ch)
            assertNull("$ch takes no tone", e.toned)
            assertEquals(ch, EmojiTone.apply(e, 3))
        }
    }

    @Test
    fun sequencesToneEveryPersonAndDropTheSelectorTheToneReplaces() {
        // Both people, not the handshake between them.
        assertEquals("🧑🏽‍🤝‍🧑🏽", EmojiTone.apply(entry("🧑‍🤝‍🧑"), 3))
        assertEquals("👩🏾‍💻", EmojiTone.apply(entry("👩‍💻"), 4))
        // The pointing finger is U+261D U+FE0F; the toned form has no selector.
        assertEquals("☝🏿", EmojiTone.apply(entry("☝️"), 5))
        assertEquals("🏃🏻‍♀️", EmojiTone.apply(entry("🏃‍♀️"), 1))
    }

    @Test
    fun theAssetCarriesATonedFormForEveryPersonAndHand() {
        val toned = catalog.entries.filter { it.toned != null }
        assertEquals(323, toned.size)
        // Each toned form is the plain one with a light modifier added, the selector it replaces aside.
        val light = 0x1F3FB
        for (e in toned) {
            val t = e.toned!!
            assertTrue(e.ch, t.codePoints().anyMatch { it == light })
            assertTrue(e.ch, t.codePoints().noneMatch { it in 0x1F3FC..0x1F3FF })
            val plain = t.replace(String(Character.toChars(light)), "").replace("️", "")
            assertEquals(e.ch, e.ch.replace("️", ""), plain)
        }
        assertTrue(entry("👍").toned != null)
        assertTrue(entry("🧒").toned != null)
    }

    @Test
    fun theStoredNamesMatchTheSettingsValues() {
        assertEquals(0, EmojiTone.of("none"))
        assertEquals(3, EmojiTone.of("medium"))
        assertEquals(5, EmojiTone.of("dark"))
        assertEquals("an unknown value is none", 0, EmojiTone.of("purple"))
        assertEquals(0, EmojiTone.of(null))
        val direct = Paths.get("src/main/res/values/arrays.xml")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/arrays.xml")
        assumeTrue(Files.exists(p))
        val xml = String(Files.readAllBytes(p))
        val block = Regex("""<string-array name="emoji_tone_values"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)!!.groupValues[1]
        assertEquals(EmojiTone.NAMES, Regex("<item>([^<]*)</item>").findAll(block).map { it.groupValues[1] }.toList())
    }

    @Test
    fun aTonedPickCountsForItsEmojiInTheSearch() {
        val words = listOf(EmojiAssetFiles.words("en"))
        val tone = 3
        val shown = { e: EmojiCatalog.Entry -> EmojiTone.apply(e, tone) }
        val plain = EmojiSearch.rank("hand", catalog.entries, words, shown = shown).map(shown)
        // The tenth result, picked often in its toned form, rises to the top.
        val picked = plain[9]
        assertTrue(picked, picked.codePoints().anyMatch { it == 0x1F3FD })
        val ranked = EmojiSearch.rank("hand", catalog.entries, words, mapOf(picked to 50), shown = shown).map(shown)
        assertEquals(picked, ranked.first())
    }
}
