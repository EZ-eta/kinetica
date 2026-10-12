package com.kinetica.keyboard.ime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every seeded word tells the bar a word is pending before its decode runs.
 *
 * A delete empties the bar and reloads the word under the cursor. Until the decode lands the bar
 * holds no words and no pending word, which is the shortcut row's cue, so the row blinked on
 * every backspace into a word. The IME path needs a live editor, so the guard is a source scan.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class SeedMarksPendingSourceTest {

    private fun source(): String {
        val rel = "src/main/java/com/kinetica/keyboard/ime/KineticaIME.kt"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("KineticaIME.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun everySeedIsFollowedByThePendingMark() {
        val src = source()
        val seeds = Regex("""\.seed\(""").findAll(src).map { it.range.first }.toList()
        assertTrue("no seed call found", seeds.isNotEmpty())
        for (at in seeds) {
            // The rest of the enclosing function: up to the next member at class indent.
            val end = Regex("""\n    (private |override |internal |fun |val |var |/\*\*)""")
                .find(src, at)?.range?.first ?: src.length
            val line = src.substring(0, at).count { it == '\n' } + 1
            assertTrue(
                "seed at KineticaIME.kt:$line leaves the bar without a pending word",
                src.substring(at, end).contains("updateBarWordPending()"),
            )
        }
    }
}
