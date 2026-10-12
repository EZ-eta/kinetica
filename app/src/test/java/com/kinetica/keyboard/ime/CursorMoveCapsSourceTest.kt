package com.kinetica.keyboard.ime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A cursor moved by the user re-decides the shift from the text before the new position.
 *
 * The shift is otherwise decided only after the keyboard's own edits, so a period typed and the
 * cursor moved back into the sentence capitalized the next letter. `onUpdateSelection` needs a
 * live editor, so the guard is a source scan, like `SteppedSliderSourceTest`. The decision
 * itself is `startsNewSentence`, covered by `SentenceCapsTest`.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class CursorMoveCapsSourceTest {

    private fun source(): String {
        val rel = "src/main/java/com/kinetica/keyboard/ime/KineticaIME.kt"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("KineticaIME.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun aUserCursorMoveRedecidesTheShift() {
        val src = source()
        val start = src.indexOf("override fun onUpdateSelection(")
        require(start >= 0) { "onUpdateSelection not found" }
        val end = src.indexOf("\n    }\n", start)
        val body = src.substring(start, end)
        // Past the early return for the keyboard's own reports: only a user move re-decides.
        val userPath = body.substring(body.indexOf("return\n"))
        assertTrue(
            "onUpdateSelection's user path never calls updateAutoShift",
            userPath.contains("updateAutoShift()"),
        )
    }
}
