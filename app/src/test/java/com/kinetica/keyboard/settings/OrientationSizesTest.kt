package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ui.BarMetrics
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landscape keeps its own margins, gaps, handle and bar, started from the portrait values so an
 * upgrade moves nothing, and found in Size and layout under its own header.
 */
class OrientationSizesTest {

    private fun migrated(vararg stored: Pair<String, Any>): EditablePrefs =
        EditablePrefs(mapOf(*stored)).also { OrientationPrefs.migrate(it) }

    @Test
    fun anUpgradeCopiesEveryStoredPortraitSizeToLandscape() {
        val stored = Prefs.LANDSCAPE_TWINS.mapIndexed { i, k -> k to 10 + i }.toTypedArray()
        val p = migrated(*stored)
        for ((k, v) in stored) assertEquals(k, v, p.values[Prefs.landscapeTwin(k)])
    }

    @Test
    fun aStoredLandscapeSizeIsNeverOverwritten() {
        val twin = Prefs.landscapeTwin(Prefs.SIDE_PAD_DP)
        val p = migrated(Prefs.SIDE_PAD_DP to 12, twin to 4)
        assertEquals(4, p.values[twin])
    }

    @Test
    fun anUntouchedPortraitSizeWritesNothing() {
        val p = migrated()
        assertTrue(p.values.isEmpty())
    }

    @Test
    fun landscapeReadsItsOwnSizesAndPortraitKeepsTheOriginals() {
        val prefs = MapPrefs(
            mapOf(
                Prefs.SIDE_PAD_DP to 8, Prefs.landscapeTwin(Prefs.SIDE_PAD_DP) to 20,
                Prefs.SUGGESTION_BAR_DP to 44, Prefs.landscapeTwin(Prefs.SUGGESTION_BAR_DP) to 30,
                Prefs.DRAG_HANDLE_DP to 20, Prefs.landscapeTwin(Prefs.DRAG_HANDLE_DP) to 0,
                Prefs.HOME_ROW_SPREAD_PCT to 0, Prefs.landscapeTwin(Prefs.HOME_ROW_SPREAD_PCT) to 40,
                Prefs.BOTTOM_PAD_DP to 0, Prefs.landscapeTwin(Prefs.BOTTOM_PAD_DP) to 6,
                Prefs.BAR_TEXT_PCT to 150, Prefs.landscapeTwin(Prefs.BAR_TEXT_PCT) to 100,
                Prefs.RECENT_TEXT_PCT to 150, Prefs.landscapeTwin(Prefs.RECENT_TEXT_PCT) to 90,
            ),
        )
        val upright = KeyboardConfig.from(prefs, landscape = false)
        val sideways = KeyboardConfig.from(prefs, landscape = true)
        assertEquals(8, upright.sidePadDp)
        assertEquals(20, sideways.sidePadDp)
        assertEquals(44, upright.suggestionBarDp)
        assertEquals(30, sideways.suggestionBarDp)
        assertEquals(20, upright.dragHandleDp)
        assertEquals(0, sideways.dragHandleDp)
        assertEquals(40, sideways.homeRowSpreadPct)
        assertEquals(6, sideways.bottomPadDp)
        assertTrue(sideways.barTextScale < upright.barTextScale)
        assertTrue(sideways.recentTextScale < upright.recentTextScale)
    }

    @Test
    fun aLandscapeSizeNotStoredYetReadsThePortraitOne() {
        val prefs = MapPrefs(mapOf(Prefs.SIDE_PAD_DP to 16, Prefs.SUGGESTION_BAR_DP to 36))
        val sideways = KeyboardConfig.from(prefs, landscape = true)
        assertEquals(16, sideways.sidePadDp)
        assertEquals(36, sideways.suggestionBarDp)
    }

    @Test
    fun everyPlaceThatMigratesTheColoursCopiesTheSizes() {
        // Keyboard start, settings before inflating, and a restore: inflating first stores the
        // shipped default under the landscape key and the portrait value is lost to it.
        for (rel in listOf("ime/KineticaIME.kt", "settings/KeyboardPrefsFragment.kt", "settings/BackupRun.kt")) {
            val src = read("src/main/java/com/kinetica/keyboard/$rel")
            assertTrue("$rel copies the sizes", "OrientationPrefs.migrate" in src)
            assertTrue("$rel copies after the colours", src.indexOf("OrientationPrefs.migrate") > src.indexOf("ColorPrefs.migrate"))
        }
    }

    @Test
    fun everyLandscapeSizeSitsInTheLandscapeGroupWithItsPreview() {
        val rows = PrefsXml.category(read("src/main/res/xml/keyboard_prefs.xml"), SizeGroups.LANDSCAPE_ROWS)
        for (k in Prefs.LANDSCAPE_TWINS.map(Prefs::landscapeTwin) + Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE) {
            assertTrue("$k is not in the landscape group", "android:key=\"$k\"" in rows)
        }
        assertTrue("android:key=\"pref_size_preview_landscape\"" in rows)
        val portrait = PrefsXml.category(read("src/main/res/xml/keyboard_prefs.xml"), SizeGroups.PORTRAIT_ROWS)
        assertTrue("android:key=\"pref_size_preview\"" in portrait)
        assertFalse("landscape in the portrait group", "_landscape\"" in portrait)
    }

    @Test
    fun eachLandscapeSliderOffersWhatItsPortraitSliderOffers() {
        val xml = read("src/main/res/xml/keyboard_prefs.xml")
        for (k in Prefs.LANDSCAPE_TWINS) {
            val twin = Prefs.landscapeTwin(k)
            for (attr in listOf("android:defaultValue", "android:max", "app:min")) {
                // The one exception: the landscape bar goes lower than portrait's.
                if (k == Prefs.SUGGESTION_BAR_DP && attr == "app:min") continue
                assertEquals("$twin $attr", attr(xml, k, attr), attr(xml, twin, attr))
            }
        }
    }

    @Test
    fun theLandscapeBarGoesLowerThanPortrait() {
        val xml = read("src/main/res/xml/keyboard_prefs.xml")
        val twin = Prefs.landscapeTwin(Prefs.SUGGESTION_BAR_DP)
        assertEquals(BarMetrics.MIN_DP.toInt().toString(), attr(xml, Prefs.SUGGESTION_BAR_DP, "app:min"))
        assertEquals(BarMetrics.MIN_LANDSCAPE_DP.toInt().toString(), attr(xml, twin, "app:min"))
        val prefs = MapPrefs(mapOf(Prefs.SUGGESTION_BAR_DP to 16, twin to 16))
        assertEquals(16, KeyboardConfig.from(prefs, landscape = true).suggestionBarDp)
        assertEquals(24, KeyboardConfig.from(prefs, landscape = false).suggestionBarDp)
    }

    @Test
    fun theGroupOfTheOrientationHeldStartsOpen() {
        assertTrue(SizeGroups.startsOpen(SizeGroups.PORTRAIT_ROWS, landscape = false))
        assertFalse(SizeGroups.startsOpen(SizeGroups.LANDSCAPE_ROWS, landscape = false))
        assertTrue(SizeGroups.startsOpen(SizeGroups.LANDSCAPE_ROWS, landscape = true))
        assertFalse(SizeGroups.startsOpen(SizeGroups.PORTRAIT_ROWS, landscape = true))
    }

    private fun attr(xml: String, key: String, name: String): String? {
        val element = Regex("""<[\w.]+\s+android:key="$key"[^>]*>""").find(xml)?.value ?: return null
        return Regex("""$name="([^"]*)"""").find(element)?.groupValues?.get(1)
    }

    private fun read(path: String): String {
        val p = listOf(Paths.get(path), Paths.get("app/$path")).first { Files.exists(it) }
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
