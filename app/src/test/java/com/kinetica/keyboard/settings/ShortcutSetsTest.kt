package com.kinetica.keyboard.settings

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bar and the ?123 hold menu read separate shortcut sets.
 *
 * With one shared set, taking the gear off the bar also took it off the hold, the keyboard's
 * only route to settings unless a chord or an edge swipe was bound to it.
 */
class ShortcutSetsTest {

    @Test
    fun theBarAndTheMenuReadTheirOwnSets() {
        val prefs = MapPrefs(
            mapOf(
                Prefs.BAR_ACTIONS to setOf(EditorAction.UNDO.name),
                Prefs.MENU_ACTIONS to setOf(EditorAction.SETTINGS.name),
            ) + HUE_SET,
        )
        val config = KeyboardConfig.from(prefs)
        assertEquals(setOf(EditorAction.UNDO.name), config.barActions)
        assertEquals(setOf(EditorAction.SETTINGS.name), config.menuActions)
    }

    @Test
    fun anUntouchedMenuShowsTheShippedDefaultNotTheBarsChoice() {
        // Nothing to migrate: the row and the menu's own set shipped in the same release, so no
        // published build has a customised bar set for the menu to inherit.
        val prefs = MapPrefs(mapOf(Prefs.BAR_ACTIONS to setOf(EditorAction.UNDO.name)) + HUE_SET)
        assertEquals(ActionRow.DEFAULT, KeyboardConfig.from(prefs).menuActions)
    }

    private companion object {
        val HUE_SET: Map<String, Any> = MapPrefs.HUE_SET
    }
}
