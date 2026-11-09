package com.kinetica.keyboard.settings

import com.kinetica.keyboard.onboarding.SetupSteps
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The guided setup's screens against the settings tree. The setup copies its rows, so a default,
 * a range or a list changed in one file and not the other would store two meanings under one key.
 * Read off disk, like [PreferenceTreeTest]: a fail-first check needs `--rerun-tasks`.
 */
class SetupPrefsTest {

    private fun setupXml(): String {
        val direct = Paths.get("src/main/res/xml/setup_prefs.xml")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/xml/setup_prefs.xml")
        assumeTrue("setup_prefs.xml not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    /** Each keyed element, whitespace folded, by key. */
    private fun elements(xml: String): Map<String, String> =
        Regex("""<[\w.]+\s[^>]*?android:key="([^"]+)"[^>]*>""").findAll(xml)
            .associate { it.groupValues[1] to it.value.replace(Regex("""\s+"""), " ") }

    private val stepScreens = SetupSteps.Step.entries.mapNotNull { it.prefScreen }

    @Test
    fun everySetupRowIsTheSettingsRow() {
        val settings = elements(PrefsXml.read())
        val rows = elements(setupXml()).filterKeys { it !in stepScreens }
        assertTrue(rows.isNotEmpty())
        for ((key, element) in rows) {
            assertEquals(key, settings[key], element)
        }
    }

    @Test
    fun eachChoiceStepHasItsScreen() {
        val screens = PrefsXml.walk(setupXml()).second.keys
        assertEquals(stepScreens.toSet(), screens)
    }

    @Test
    fun thePreviewsShareAScreenWithWhatTheyShow() {
        val where = PrefsXml.screenOf(setupXml())
        assertEquals("setup_height", where[Prefs.KEYBOARD_HEIGHT_PCT])
        assertEquals("setup_height", where["pref_size_preview"])
        for (k in listOf(Prefs.THEME_MODE, Prefs.THEME_BRIGHTNESS, Prefs.THEME_COLOR_HEX, "pref_theme_preview")) {
            assertEquals(k, "setup_theme", where[k])
        }
    }

    @Test
    fun everyDependencyLivesWithItsParent() {
        // A dependent without its parent on the screen throws at inflation.
        val xml = setupXml()
        val where = PrefsXml.screenOf(xml)
        for (m in Regex("""android:key="([^"]+)"[^>]*?android:dependency="([^"]+)"""").findAll(xml)) {
            val (key, parent) = m.destructured
            assertNotNull(parent, where[parent])
            assertEquals(key, where[parent], where[key])
        }
    }
}
