package com.kinetica.keyboard.onboarding

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The setup activity's lines a JVM test cannot run: it needs a Context. Each guard is a source
 * scan of the line that carries the behaviour. Read off disk: a fail-first check needs `--rerun-tasks`.
 */
class SetupSourceTest {

    private fun source(): String {
        val rel = "src/main/java/com/kinetica/keyboard/onboarding/MainActivity.kt"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("MainActivity.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "$signature not found" }
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    /** A generated id differs after a rotation, and the restored step fragment has nowhere to go. */
    @Test
    fun theStepHostHasAFixedId() {
        val src = source()
        assertTrue(src.contains("id = R.id.setup_body"))
        assertFalse(src.contains("generateViewId"))
    }

    /** The keyboard picker is a dialog: without the focus callback, choosing Kinetica moves nothing. */
    @Test
    fun theFocusReturnRereadsTheKeyboardState() {
        val focus = body(source(), "override fun onWindowFocusChanged(")
        assertTrue(focus, focus.contains("if (hasFocus) refresh()"))
    }
}
