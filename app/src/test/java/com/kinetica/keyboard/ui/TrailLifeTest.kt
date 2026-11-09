package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The trail kept until its word ends, a choice off by default: a kept point stays whole however
 * old, and once the word ends it fades over the usual life from then.
 */
class TrailLifeTest {

    @Test
    fun aPointFadesOverItsLife() {
        assertEquals(1f, TrailLife.strength(kept = false, t = 1_000, now = 1_000), 0f)
        assertEquals(0.5f, TrailLife.strength(kept = false, t = 1_000, now = 1_125), 1e-6f)
        assertFalse(TrailLife.expired(kept = false, t = 1_000, now = 1_250))
        assertTrue(TrailLife.expired(kept = false, t = 1_000, now = 1_251))
    }

    @Test
    fun aKeptPointStaysWholeUntilItsWordEnds() {
        // A long word drawn over two seconds keeps its first key's point.
        assertEquals(1f, TrailLife.strength(kept = true, t = 1_000, now = 3_000), 0f)
        assertFalse(TrailLife.expired(kept = true, t = 1_000, now = 60_000))
    }

    private fun source(file: String): String {
        val rel = "src/main/java/com/kinetica/keyboard/$file"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$file not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    /**
     * The view paths have no JVM reach, so their lines are read: the renderer ages points through
     * [TrailLife] and dates the kept ones to the release, every word end releases, and a field that
     * teaches nothing keeps nothing. Read off disk: a fail-first check needs `--rerun-tasks`.
     */
    @Test
    fun theWordEndReleasesTheKeptTrail() {
        val renderer = source("ui/TrailRenderer.kt")
        assertTrue(renderer.contains("TrailLife.strength(p.kept, p.t, now)"))
        assertTrue(renderer.contains("TrailLife.expired(trail.first().kept, trail.first().t, now)"))
        val release = renderer.substring(renderer.indexOf("fun release("))
        assertTrue("a released point keeps its old date", release.substring(0, release.indexOf("\n    }\n")).contains("p.t = now"))
        val ime = source("ime/KineticaIME.kt")
        val ended = ime.substring(ime.indexOf("internal fun wordEnded()"))
        assertTrue("a word end keeps the trail", ended.substring(0, ended.indexOf("\n    }\n")).contains("keyboardView?.releaseTrail()"))
        assertTrue(source("ime/KeyboardViews.kt").contains("kv.keepTrail = ime.config.keepTrail && !ime.editorState.teachesNothing"))
    }
}
