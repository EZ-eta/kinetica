package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every seeded word tells the bar a word is pending before its decode runs.
 *
 * A delete empties the bar and reloads the word under the cursor. Until the decode lands the bar
 * holds no words and no pending word, which is the shortcut row's cue, so the row blinked on
 * every backspace into a word. The IME path needs a live editor, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class SeedMarksPendingSourceTest {

    @Test
    fun everySeedIsFollowedByThePendingMark() {
        var seen = 0
        for (source in ImeSources.all()) {
            val src = source.text
            for (match in Regex("""\.seed\(""").findAll(src)) {
                val at = match.range.first
                seen++
                // The rest of the enclosing function: up to the next member at class indent.
                val end = Regex("""\n    (private |override |internal |fun |val |var |/\*\*)""")
                    .find(src, at)?.range?.first ?: src.length
                assertTrue(
                    "seed at ${source.name}:${source.lineOf(at)} leaves the bar without a pending word",
                    src.substring(at, end).contains("updateBarWordPending()"),
                )
            }
        }
        assertTrue("no seed call found", seen > 0)
    }
}
