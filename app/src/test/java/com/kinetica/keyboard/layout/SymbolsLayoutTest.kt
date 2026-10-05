package com.kinetica.keyboard.layout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the symbols pages can type, read off the shipped assets.
 *
 * A layout edit that drops a key is otherwise invisible: the JSON still parses and the
 * keyboard still comes up. A missing `/`, once only a long-press alternate of `b`, went
 * unnoticed that way.
 *
 * The files are read as text and matched, not parsed: the JVM test runtime stubs `org.json`,
 * so [LayoutLoader] cannot be called here (see LayoutMutationsTest). Presence needs no geometry.
 *
 * The assets are read off disk, so Gradle does not see them as inputs and an asset-only edit
 * leaves this task UP-TO-DATE; a fail-first check needs `--rerun-tasks`, as RealDictionaryTest
 * does.
 */
class SymbolsLayoutTest {

    private fun layoutPath(name: String): Path {
        val direct = Paths.get("src/main/assets/layouts/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/layouts/$name")
    }

    private fun read(name: String): String {
        val p = layoutPath(name)
        assumeTrue("layout $name not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    /** True when [ch] is the `output` of a key, i.e. reachable by a plain tap. */
    private fun tappable(json: String, ch: String): Boolean =
        json.contains("\"output\": \"$ch\"")

    /** True when [ch] sits in some key's `alternates`, i.e. reachable by long press. */
    private fun onLongPress(json: String, ch: String): Boolean =
        Regex("\"alternates\"\\s*:\\s*\\[([^]]*)]").findAll(json)
            .any { it.groupValues[1].contains("\"$ch\"") }

    @Test
    fun theForwardSlashIsTappableOnTheSymbolsPage() {
        // A tap, not an alternate: the reporter knew about the long press and preferred the
        // symbols layer anyway.
        val page1 = read("symbols.json")
        assertTrue("/ must be tappable on symbols page 1", tappable(page1, "/"))
    }

    @Test
    fun theSemicolonSurvivedTheSlashTakingItsSlot() {
        // `/` took `;`'s cell instead of shrinking the backspace, a slide target users like.
        // `;` moved onto `:`, where the two belong together.
        val page1 = read("symbols.json")
        assertTrue("; must still be reachable", tappable(page1, ";") || onLongPress(page1, ";"))
        assertTrue(": must stay tappable", tappable(page1, ":"))
    }

    @Test
    fun theSymbolsPagesStillCarryTheirInventory() {
        // A floor, not an exhaustive list: these are the marks whose absence would be a
        // bug report. Both pages together, because `?123` reaches either.
        val both = read("symbols.json") + read("symbols2.json")
        val required = listOf(
            "@", "#", "$", "%", "&", "-", "+", "(", ")",
            "=", "*", ":", "!", "?", ",", ".", "/",
            "[", "]", "{", "}", "<", ">", "^", "~", "|",
        )
        for (ch in required) {
            assertTrue("$ch must be tappable somewhere on the symbols pages", tappable(both, ch))
        }
        // The digits are page 1's top row.
        for (d in '0'..'9') {
            assertTrue("$d must be tappable", tappable(both, d.toString()))
        }
    }

    @Test
    fun theNumpadCanTypeASpace() {
        // Without a space key, a number with a space in it had to be finished on another page.
        val numpad = read("numpad.json")
        assertTrue("the numpad needs a space key", numpad.contains("\"type\": \"space\""))
    }

    @Test
    fun theNumpadStillCarriesItsInventory() {
        // The space took a new row, not a neighbour's cell, so nothing here moves. A layout
        // edit that drops a key is otherwise invisible.
        val numpad = read("numpad.json")
        for (d in '0'..'9') {
            assertTrue("$d must stay tappable on the numpad", tappable(numpad, d.toString()))
        }
        for (ch in listOf("-", ".", ",")) {
            assertTrue("$ch must stay tappable on the numpad", tappable(numpad, ch))
        }
        for (t in listOf("backspace", "enter", "mode_alpha")) {
            assertTrue("the numpad needs its $t key", numpad.contains("\"type\": \"$t\""))
        }
    }
}
