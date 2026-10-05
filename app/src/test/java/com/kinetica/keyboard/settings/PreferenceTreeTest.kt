package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shape of the settings tree, read off the shipped XML.
 *
 * The settings are nested screens, three levels at most, and nesting has one way to fail hard:
 * `android:dependency` is resolved against the current root and each subscreen is inflated as
 * its own root, so a dependent separated from its parent throws at inflation.
 * [everyDependencyLivesWithItsParent] guards that.
 *
 * Parsed as text, like `SymbolsLayoutTest`, because the question is about structure. The file
 * is read off disk, so Gradle does not treat it as an input and a fail-first check here needs
 * `--rerun-tasks`.
 */
class PreferenceTreeTest {

    private fun prefsXml(): String = PrefsXml.read()

    /** Every `android:key`, in file order. */
    private fun keys(xml: String): List<String> =
        Regex("""android:key="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()

    /**
     * The key of the nested `<PreferenceScreen>` each row sits in, or null for the top level.
     * A nested screen's own key is placed in the screen that holds it.
     *
     * A stack of open screens, because the tree is three levels deep; a screen's key is the
     * first `android:key` after its open tag.
     */
    private fun screenOf(xml: String): Map<String, String?> = walk(xml).first

    /** How many nested screens down each nested screen is: 1 for a top-level row. */
    private fun depthOf(xml: String): Map<String, Int> = walk(xml).second

    private fun walk(xml: String): Pair<Map<String, String?>, Map<String, Int>> = PrefsXml.walk(xml)

    @Test
    fun everyPreferenceIsInsideExactlyOneScreen() {
        val xml = prefsXml()
        val all = keys(xml)
        assertEquals("no key may appear twice", all.size, all.toSet().size)
        val placed = screenOf(xml)
        assertEquals("every key must be placed", all.toSet(), placed.keys)
    }

    @Test
    fun everyDependencyLivesWithItsParent() {
        // The crash: Preference.registerDependency resolves against the current root screen
        // and throws IllegalStateException when the parent is on a different one.
        val xml = prefsXml()
        val where = screenOf(xml)
        val blocks = xml.split(Regex("""(?=<\w)"""))
        var checked = 0
        for (b in blocks) {
            val dep = Regex("""android:dependency="([^"]+)"""").find(b) ?: continue
            val key = Regex("""android:key="([^"]+)"""").find(b)!!.groupValues[1]
            val parent = dep.groupValues[1]
            assertTrue("$key depends on $parent, which is not in the tree", where.containsKey(parent))
            assertEquals(
                "$key and its parent $parent must share a screen or the subscreen crashes",
                where[parent], where[key],
            )
            checked++
        }
        assertTrue("the five known dependencies must be found", checked >= 5)
    }

    @Test
    fun theVersionRowStaysAtTheTopLevel() {
        // showVersion only resolves on the screen it is inflated with, so a Version row in
        // a submenu would silently show no version at all.
        assertEquals(null, screenOf(prefsXml())["pref_version"])
    }

    @Test
    fun theThemePreviewSharesAScreenWithTheHueSlider() {
        // wireThemePreview returns early when the preview is absent, taking the hue, mode
        // and brightness listeners with it. The failure is a dead swatch, not a crash.
        val where = screenOf(prefsXml())
        for (k in listOf("pref_theme_hue", "pref_theme_mode", "pref_theme_brightness")) {
            assertEquals("$k must sit with the preview", where["pref_theme_preview"], where[k])
        }
    }

    @Test
    fun theShortcutChoosersShareAScreenWithTheBarHeight() {
        // The bar's height row is where anyone looks for a setting about the bar; under Typing
        // and predictions it could not be found. The ?123 chooser sits beside the bar's so the
        // two sets read as a pair.
        val where = screenOf(prefsXml())
        assertEquals(where["pref_suggestion_bar_dp"], where["pref_bar_actions"])
        assertEquals(where["pref_bar_actions"], where["pref_menu_actions"])
    }

    @Test
    fun noScreenIsMoreThanThreeLevelsDown() {
        // Progressive disclosure: the everyday settings one level down, the expert ones a level
        // below that, and nothing deeper.
        val deep = depthOf(prefsXml()).filterValues { it > 2 }
        assertTrue("screens too deep: $deep", deep.isEmpty())
        assertTrue("the nested screens must be found", depthOf(prefsXml()).values.any { it == 2 })
    }

    @Test
    fun everySizeSliderSharesAScreenWithAPreview() {
        // The fragment makes a size slider update continuously only when its screen holds a
        // size preview, so a slider moved away from one stops showing its effect live.
        val where = screenOf(prefsXml())
        val previews = listOf("pref_size_preview", "pref_bar_preview").map { where[it] }.toSet()
        for (k in listOf(
            "pref_keyboard_height_pct", "pref_keyboard_height_pct_landscape", "pref_suggestion_bar_dp",
            "pref_side_pad_dp", "pref_bottom_pad_dp", "pref_drag_handle_dp", "pref_landscape_split_gap_pct",
            "pref_home_row_spread",
        )) {
            assertTrue("$k is on ${where[k]}, which has no size preview", where.containsKey(k) && where[k] in previews)
        }
    }

    @Test
    fun theScreensTheKeyboardOpensStillExist() {
        // The long-press menu's "edit" opens this screen by key.
        assertTrue(depthOf(prefsXml()).containsKey(SettingsSynonyms.LONGPRESS_GROUP))
    }

    @Test
    fun practiseTwoThumbsLeadsLearnKinetica() {
        // The order: the tutor on top, the tips below it.
        val learn = screenOf(prefsXml()).filterValues { it == "pref_group_learn" }.keys
        assertEquals("pref_tutor_screen", learn.first())
        assertTrue("the tips must be found", learn.count { it.startsWith("tip_") } >= 30)
    }

    @Test
    fun theTopLevelStaysShortEnoughToScan() {
        // A flat list of 52 rows could not be navigated.
        val top = screenOf(prefsXml()).filterValues { it == null }
        assertTrue("top level is ${top.size} rows: ${top.keys}", top.size <= 12)
    }
}

/** The shipped settings XML, read off disk, and its screens; shared with [LearnTipsTest]. */
internal object PrefsXml {

    fun read(): String {
        val direct = Paths.get("src/main/res/xml/keyboard_prefs.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/xml/keyboard_prefs.xml")
        assumeTrue("keyboard_prefs.xml not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    /** Each key's screen (null: the top level), and each nested screen's depth. */
    fun walk(xml: String): Pair<Map<String, String?>, Map<String, Int>> {
        val out = LinkedHashMap<String, String?>()
        val depth = LinkedHashMap<String, Int>()
        val stack = ArrayList<String?>()
        var pendingOpen = false
        val token = Regex("""<PreferenceScreen\b|</PreferenceScreen>|android:key="([^"]+)"""")
        for (m in token.findAll(xml)) {
            when {
                // The first open tag is the root, which has no key.
                m.value.startsWith("<PreferenceScreen") -> if (stack.isEmpty()) stack.add(null) else pendingOpen = true
                m.value.startsWith("</PreferenceScreen") -> stack.removeAt(stack.size - 1)
                else -> {
                    val key = m.groupValues[1]
                    out[key] = stack.last()
                    if (pendingOpen) {
                        depth[key] = stack.size
                        stack.add(key)
                        pendingOpen = false
                    }
                }
            }
        }
        return out to depth
    }

    fun screenOf(xml: String): Map<String, String?> = walk(xml).first
}
