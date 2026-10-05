package com.kinetica.keyboard.settings

import android.content.SharedPreferences
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
        // A stored hue keeps KeyboardConfig off the retired colour-string migration, which
        // calls android.graphics.Color and has no JVM implementation.
        val HUE_SET: Map<String, Any> = mapOf(Prefs.THEME_HUE to 233)
    }

    /** Read-only preferences backed by a map; every unset key answers its default. */
    private class MapPrefs(private val values: Map<String, Any>) : SharedPreferences {
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
