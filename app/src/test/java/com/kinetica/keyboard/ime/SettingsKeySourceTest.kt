package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings key pressed in the settings' own try field keeps the screen in use. Starting the
 * settings again with CLEAR_TOP rebuilt them at the top. The key path needs a service, so the guard
 * is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class SettingsKeySourceTest {

    @Test
    fun theTryFieldHidesTheKeyboardInsteadOfRestartingTheSettings() {
        val src = ImeSources.declaring("fun openSettings(").text
        val start = src.indexOf("fun openSettings(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val hide = body.indexOf("ime.requestHideSelf(0)")
        val back = body.indexOf("if (screen == null && ime.editorState.ownTryField) return")
        assertTrue("the try field still restarts the settings", back >= 0)
        assertTrue("the keyboard must hide first", hide in 0 until back)
        assertTrue(back < body.indexOf("ime.startActivity("))
        val ime = ImeSources.declaring("class KineticaIME").text
        assertTrue("the field's state never learns the own package", ime.contains("EditorState.from(attribute, packageName)"))
    }
}
