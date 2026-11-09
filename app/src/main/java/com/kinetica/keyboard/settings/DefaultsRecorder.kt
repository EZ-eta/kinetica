package com.kinetica.keyboard.settings

import androidx.preference.PreferenceDataStore

/**
 * Answers every read with the default it was given, remembers it, and keeps no write.
 *
 * The changed-settings screen inflates the whole tree through it to learn each row's default.
 * A row writes while it inflates (a switch, a list, a slider persist the value they just read),
 * and the base store throws on a write: the screen crashed on open. A row also reads again with
 * a guard value before writing, so the first read of a key is its default and the later ones are
 * kept out.
 */
internal class DefaultsRecorder : PreferenceDataStore() {
    private val firstSeen = HashMap<String, Any?>()

    /** Each key's default, as the row first asked for it. */
    val seen: Map<String, Any?> get() = firstSeen

    private fun <T> record(key: String, value: T): T = value.also { if (!firstSeen.containsKey(key)) firstSeen[key] = it }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = record(key, defValue)
    override fun getInt(key: String, defValue: Int): Int = record(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = record(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = record(key, defValue)
    override fun getString(key: String, defValue: String?): String? = record(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues.also { record(key, it?.toSet()) }

    override fun putBoolean(key: String, value: Boolean) = Unit
    override fun putInt(key: String, value: Int) = Unit
    override fun putLong(key: String, value: Long) = Unit
    override fun putFloat(key: String, value: Float) = Unit
    override fun putString(key: String, value: String?) = Unit
    override fun putStringSet(key: String, values: MutableSet<String>?) = Unit
}
