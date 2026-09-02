package com.kinetica.keyboard.layout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the symbols pages can actually type, read off the shipped assets.
 *
 * Reported: there is no forward slash anywhere on the symbols keyboard. It was true - `/`
 * existed only as a long-press alternate of `b` on the alpha layer - and it was not caught
 * because nothing asserted the inventory. A layout edit that drops a key is invisible
 * otherwise: the JSON still parses and the keyboard still comes up.
 *
 * The files are read as text and matched, not parsed: the JVM test runtime stubs `org.json`,
 * so [LayoutLoader] cannot be called here (see LayoutMutationsTest). That is enough for the
 * question this asks, which is about presence rather than geometry.
 *
 * One thing to know when checking a change here: the assets are read straight off disk, so
 * Gradle does not see them as inputs to this task and an asset-only edit leaves it
 * UP-TO-DATE with the previous result. `--rerun-tasks` is what makes a fail-first check
 * actually run. RealDictionaryTest reads the wordlists the same way and has the same
 * property.
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
        // The report, and the reason it is a tap rather than an alternate: the reporter
        // knew about the long press and preferred the symbols layer anyway.
        val page1 = read("symbols.json")
        assertTrue("/ must be tappable on symbols page 1", tappable(page1, "/"))
    }

    @Test
    fun theSemicolonSurvivedTheSlashTakingItsSlot() {
        // `/` took `;`'s cell rather than shrinking the backspace, which is a slide target
        // and was praised in the field (R20). `;` moved onto `:`, which is where the two
        // belong together anyway.
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
}
