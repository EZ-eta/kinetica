package com.kinetica.keyboard.settings

import android.content.SharedPreferences

/** Read-only preferences backed by a map; every unset key answers its default. */
internal class MapPrefs(private val values: Map<String, Any>) : SharedPreferences {
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

    companion object {
        /**
         * A stored hue keeps KeyboardConfig off the retired colour-string migration, which
         * calls android.graphics.Color and has no JVM implementation.
         */
        val HUE_SET: Map<String, Any> = mapOf(Prefs.THEME_HUE to 233)

        /** A config from [values], with the hue set. */
        fun config(values: Map<String, Any> = emptyMap()): KeyboardConfig =
            KeyboardConfig.from(MapPrefs(values + HUE_SET))
    }
}
