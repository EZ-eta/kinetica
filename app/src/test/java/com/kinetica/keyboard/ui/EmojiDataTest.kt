package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shipped emoji asset, checked for the first time.
 *
 * `emoji_data.json` is hand-maintained and nothing has ever read it outside the picker,
 * which parses it with no validation at all: a missing field throws `JSONException` out of
 * the `EmojiPickerView` constructor, i.e. the picker fails to open rather than degrading.
 *
 * Matched as text rather than parsed, because **the JVM test runtime stubs `org.json`** -
 * the same reason [com.kinetica.keyboard.settings.Backup] is hand-rolled. The file is read
 * off disk, so Gradle does not see it as an input and **`--rerun-tasks` is what makes a
 * fail-first check here actually run.**
 */
class EmojiDataTest {

    private fun asset(): String {
        val direct = Paths.get("src/main/assets/emoji_data.json")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/emoji_data.json")
        assumeTrue("emoji_data.json not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun values(field: String, json: String): List<String> =
        Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json).map { it.groupValues[1] }.toList()

    @Test
    fun everyEntryHasACharacterANameAndKeywords() {
        val json = asset()
        // Entries are the objects carrying "ch"; every one must also carry the other two.
        val chars = values("ch", json)
        assertTrue("the asset should hold hundreds of emoji, found ${chars.size}", chars.size > 400)
        val entries = Regex("""\{\s*"ch"\s*:.*?"kw"\s*:\s*\[.*?]\s*}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(json).count()
        assertEquals("every entry needs ch, name and kw in that order", chars.size, entries)
        assertTrue("no entry may have an empty keyword list", !json.contains(""""kw": []"""))
    }

    @Test
    fun noEntryIsDuplicated() {
        val chars = values("ch", asset())
        val dupes = chars.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("duplicated emoji: $dupes", dupes.isEmpty())
    }

    @Test
    fun noZeroWidthJoinerSequences() {
        // Standing policy: a device that cannot render a ZWJ sequence draws it as its parts,
        // which is worse than the single tofu box an unknown codepoint gives. It is also why
        // Unicode 15.1 is absent entirely, every one of its additions being a ZWJ sequence.
        val bad = values("ch", asset()).filter { it.contains('‍') }
        assertTrue("ZWJ sequences are excluded on purpose: $bad", bad.isEmpty())
    }

    @Test
    fun noSkinToneModifiers() {
        val bad = values("ch", asset()).filter { s ->
            s.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }
        }
        assertTrue("skin-tone modifiers are not carried: $bad", bad.isEmpty())
    }

    @Test
    fun theEightCategoriesAreAllPresentWithIcons() {
        val json = asset()
        val names = values("name", json)
        for (c in listOf(
            "Smileys", "Gestures", "Animals", "Food", "Activities", "Travel", "Objects", "Symbols",
        )) {
            assertTrue("category $c is missing", names.contains(c))
        }
        assertEquals("every category needs a tab icon", 8, values("icon", json).size)
    }

    @Test
    fun theRecentUnicodeAdditionsAreThere() {
        // The picker filters by Paint.hasGlyph at load, so carrying an emoji an old device
        // cannot draw costs nothing. That is what made topping the set up safe.
        val chars = values("ch", asset()).toSet()
        for (ch in listOf("🫨", "🩷", "🪿", "🫢")) {
            assertTrue("expected a Unicode 14/15 emoji to be present", chars.contains(ch))
        }
    }
}
