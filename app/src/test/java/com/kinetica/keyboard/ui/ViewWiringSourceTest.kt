package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * View wiring a JVM test cannot construct: a View needs a Context. Each guard is a source scan
 * of the one line that carries the behaviour.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class ViewWiringSourceTest {

    private fun source(file: String): String {
        val rel = "src/main/java/com/kinetica/keyboard/$file"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$file not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "$signature not found" }
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    /** Left plain, the bottom margin would show the window's colour, a band unlike the keys. */
    @Test
    fun theBottomGapTakesTheThemeBackground() {
        val applyTheme = body(source("ui/InputContainerView.kt"), "fun applyTheme(")
        assertTrue(
            "applyTheme leaves the bottom gap unpainted",
            applyTheme.contains("bottomGap.setBackgroundColor(theme.background)"),
        )
    }
}
