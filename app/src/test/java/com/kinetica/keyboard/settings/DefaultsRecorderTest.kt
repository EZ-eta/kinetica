package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The changed-settings screen inflates every row through [DefaultsRecorder]. Each call sequence
 * below is what an androidx row does on inflation: read with its default, then persist the value,
 * which first reads again with a guard value and then writes.
 */
class DefaultsRecorderTest {

    @Test
    fun aSwitchListAndSliderInflateWithoutAWriteAndKeepTheirDefault() {
        val r = DefaultsRecorder()
        // TwoStatePreference.setChecked -> persistBoolean: guard read with !value, then a write.
        val on = r.getBoolean("switch", true)
        r.getBoolean("switch", !on)
        r.putBoolean("switch", on)
        // ListPreference.setValue -> persistString: guard read with null.
        val v = r.getString("list", "normal")
        r.getString("list", null)
        r.putString("list", v)
        // SeekBarPreference.setValueInternal -> persistInt: guard read with ~value.
        val n = r.getInt("slider", 44)
        r.getInt("slider", n.inv())
        r.putInt("slider", n)
        val set = r.getStringSet("langs", mutableSetOf("en"))
        r.getStringSet("langs", null)
        r.putStringSet("langs", set)
        r.putLong("l", 1L)
        r.putFloat("f", 1f)
        assertEquals(mapOf("switch" to true, "list" to "normal", "slider" to 44, "langs" to setOf("en")), r.seen)
    }
}
