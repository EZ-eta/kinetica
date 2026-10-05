package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The long-press shape setting (#8): each stored value, and the migration from the grid switch. */
class PopupShapeTest {

    @Test
    fun eachShapeIsAGridWidthAndRowIsTheStrip() {
        assertEquals(0, KeyboardConfig.popupColumns("row", legacyGrid = false))
        assertEquals(3, KeyboardConfig.popupColumns("3", legacyGrid = false))
        assertEquals(4, KeyboardConfig.popupColumns("4", legacyGrid = false))
        assertEquals(5, KeyboardConfig.popupColumns("5", legacyGrid = false))
        assertEquals(0, KeyboardConfig.popupColumns("7", legacyGrid = false))
    }

    @Test
    fun aGridChosenEarlierStaysAGrid() {
        assertEquals(3, KeyboardConfig.popupColumns(null, legacyGrid = true))
        assertEquals(0, KeyboardConfig.popupColumns(null, legacyGrid = false))
        // Once a shape is chosen the old switch says nothing.
        assertEquals(0, KeyboardConfig.popupColumns("row", legacyGrid = true))
    }

    private fun res(rel: String): String {
        val direct = Paths.get("src/main/res/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun items(xml: String, name: String): List<String> {
        val block = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun theSettingOffersEveryShapeAndShipsTheCodeDefault() {
        val arrays = res("values/arrays.xml")
        val values = items(arrays, "popup_shape_values")
        assertEquals(listOf(0, 3, 4, 5), values.map { KeyboardConfig.popupColumns(it, legacyGrid = false) })
        assertEquals(values.size, items(arrays, "popup_shape_entries").size)
        val prefs = res("xml/keyboard_prefs.xml")
        val default = Regex("""android:key="${Prefs.POPUP_SHAPE}"[^>]*?android:defaultValue="([^"]+)"""")
            .find(prefs)?.groupValues?.get(1)
        assertEquals(Prefs.DEFAULT_POPUP_SHAPE, default)
    }
}
