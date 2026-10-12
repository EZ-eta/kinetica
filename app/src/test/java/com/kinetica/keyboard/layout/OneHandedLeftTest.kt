package com.kinetica.keyboard.layout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-handed for the left hand: the right-hand board mirrored, the same size and height (#29).
 * Left-aligned keeps the full height.
 */
class OneHandedLeftTest {

    @Test
    fun theLeftBoardMirrorsTheRightOne() {
        for (x in listOf(0f, 0.15f, 0.5f, 0.85f, 1f)) {
            val right = LayoutTransforms.oneHandedX(x, left = false)
            val mirrored = 1f - LayoutTransforms.oneHandedX(1f - x, left = true)
            assertEquals(right, mirrored, 1e-6f)
        }
        assertEquals(0f, LayoutTransforms.oneHandedX(0f, left = true), 0f)
        assertEquals(1f, LayoutTransforms.oneHandedX(1f, left = false), 1e-6f)
        // The same height either hand: anchored to the bottom.
        assertEquals(1f, LayoutTransforms.oneHandedY(1f), 1e-6f)
    }

    @Test
    fun keyWidthsAreTheSameEitherHand() {
        val w = LayoutTransforms.oneHandedX(0.6f, true) - LayoutTransforms.oneHandedX(0.5f, true)
        val h = LayoutTransforms.oneHandedY(0.5f) - LayoutTransforms.oneHandedY(0.25f)
        assertEquals(LayoutTransforms.oneHandedX(0.6f, false) - LayoutTransforms.oneHandedX(0.5f, false), w, 1e-6f)
        // Both axes scale alike, so row pitch in key widths is unchanged.
        assertEquals(0.25f / 0.1f, h / w, 1e-4f)
    }

    @Test
    fun theSettingListsIt() {
        assertEquals(LayoutMode.ONE_HANDED_LEFT, LayoutMode.fromPref("one_handed_left"))
        val direct = Paths.get("src/main/res/values/arrays.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/arrays.xml")
        assumeTrue(Files.exists(p))
        val xml = Files.newBufferedReader(p).use { it.readText() }
        val values = Regex("""<string-array name="layout_mode_values"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)!!.groupValues[1]
        val listed = Regex("""<item>([^<]*)</item>""").findAll(values).map { LayoutMode.fromPref(it.groupValues[1]) }.toSet()
        assertEquals(LayoutMode.entries.toSet(), listed)
    }
}
