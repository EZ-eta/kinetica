package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard draws the night look while the phone is dark: the config is read with the phone's
 * night bit, again when it flips, and the image is the look's own. The service has no JVM reach,
 * so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class NightLookSourceTest {

    @Test
    fun theConfigFollowsThePhonesNightThemeAndTheImageItsLook() {
        val ime = ImeSources.declaring("class KineticaIME").text
        val changed = ime.substring(ime.indexOf("override fun onConfigurationChanged("))
        assertTrue(
            "a flip of the phone's dark theme must pick the look",
            changed.substring(0, changed.indexOf("\n    }\n")).contains("LookSets.systemNight(newConfig.uiMode)"),
        )
        assertTrue(ime.contains("KeyboardConfig.from(prefs, landscape, systemNight)"))
        assertTrue(ime.contains("systemNight: Boolean = LookSets.systemNight(resources.configuration.uiMode)"))
        val views = ImeSources.declaring("class KeyboardViews").text
        assertTrue(
            "the image must be read for the look drawn",
            views.contains("backdrop.refresh(ime.config.backdropStamp, ime.config.nightLook)"),
        )
        val loader = ImeSources.declaring("class BackdropLoader").text
        assertTrue(loader.contains("BackdropStore.load(context, night)"))
    }
}
