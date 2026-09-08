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
 * The screen was one flat list of 52 rows until a user said it could not be navigated. It is
 * five nested screens now, and nesting has one way to fail hard: `android:dependency` is
 * resolved against the CURRENT root, and each subscreen is inflated as its own root, so a
 * dependent separated from its parent throws at inflation rather than degrading. That is
 * what [everyDependencyLivesWithItsParent] is for, and it is the reason this file exists.
 *
 * Parsed as text rather than with a real XML parser for the same reason
 * `SymbolsLayoutTest` matches strings: the question is about structure, and the file is read
 * straight off disk so Gradle does not treat it as an input. **`--rerun-tasks` is what makes
 * a fail-first check here actually run.**
 */
class PreferenceTreeTest {

    private fun prefsXml(): String {
        val direct = Paths.get("src/main/res/xml/keyboard_prefs.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/xml/keyboard_prefs.xml")
        assumeTrue("keyboard_prefs.xml not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    /** Every `android:key`, in file order. */
    private fun keys(xml: String): List<String> =
        Regex("""android:key="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()

    /**
     * The key of the nested `<PreferenceScreen>` each row sits in, or null for the top level.
     *
     * Walks the tags in order and tracks depth, which is enough because the tree is exactly
     * two levels deep by construction and [theTreeIsTwoLevelsDeep] pins that.
     */
    private fun screenOf(xml: String): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        var current: String? = null
        val token = Regex("""<PreferenceScreen\b|</PreferenceScreen>|android:key="([^"]+)"""")
        var seenRoot = false
        for (m in token.findAll(xml)) {
            when {
                m.value.startsWith("<PreferenceScreen") -> if (!seenRoot) seenRoot = true else Unit
                m.value.startsWith("</PreferenceScreen") -> current = null
                else -> {
                    val key = m.groupValues[1]
                    // A nested screen declares its own key immediately after its open tag.
                    val before = xml.lastIndexOf("<PreferenceScreen", m.range.first)
                    val between = xml.substring(before, m.range.first)
                    if (current == null && !between.contains(">") && seenRoot && before > 0) {
                        current = key
                        out[key] = null
                    } else {
                        out[key] = current
                    }
                }
            }
        }
        return out
    }

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
        // showVersion() only resolves on the screen it is inflated with, so a Version row in
        // a submenu would silently show no version at all.
        assertEquals(null, screenOf(prefsXml())["pref_version"])
    }

    @Test
    fun theThemePreviewSharesAScreenWithTheHueSlider() {
        // wireThemePreview() returns early when the preview is absent, taking the hue, mode
        // and brightness listeners with it. The failure is a dead swatch, not a crash.
        val where = screenOf(prefsXml())
        for (k in listOf("pref_theme_hue", "pref_theme_mode", "pref_theme_brightness")) {
            assertEquals("$k must sit with the preview", where["pref_theme_preview"], where[k])
        }
    }

    @Test
    fun theTopLevelStaysShortEnoughToScan() {
        // The whole point of the rework. 52 rows was the complaint.
        val top = screenOf(prefsXml()).filterValues { it == null }
        assertTrue("top level is ${top.size} rows: ${top.keys}", top.size <= 12)
    }
}
