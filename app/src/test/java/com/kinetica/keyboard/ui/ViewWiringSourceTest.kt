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

    /**
     * Every picked image failed with "could not be read": a bounds-only decode returns null by
     * design, and that null was taken for a stream that would not open.
     */
    @Test
    fun theImageSizeReadIsNotTakenForAFailedOpen() {
        val import = body(source("settings/BackdropStore.kt"), "fun import(")
        val decode = Regex("""decodeStream\(it, null, bounds\)[^\n]*\n[^\n]*""").find(import)?.value
        requireNotNull(decode) { "the bounds read is gone" }
        assertTrue("the bounds read's result is checked: $decode", "?:" !in decode)
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

    /** Without the wake in the frame loop, the lit keys freeze at full brightness after a lift. */
    @Test
    fun theFrameLoopRunsWhileKeysAreLit() {
        val src = source("ui/KeyboardView.kt")
        val frame = src.substring(src.indexOf("override fun doFrame("), src.indexOf("invalidate()", src.indexOf("override fun doFrame(")))
        assertTrue("doFrame stops before the lit keys fade", frame.contains("keyWake.anyLit(now)"))
    }

    /** A hidden trail still steps its hue, or rainbow lit keys stay one colour. */
    @Test
    fun theHueStepsWhenOnlyKeysLight() {
        val transition = body(source("ui/KeyboardView.kt"), "fun onEngineKeyTransition(")
        val bump = transition.indexOf("trailRenderer.bumpHue(streamId)")
        assertTrue("no hue step", bump >= 0)
        assertTrue("the hue steps only with the trail drawn", bump < transition.indexOf("swipeLook.drawsTrail"))
    }
}
