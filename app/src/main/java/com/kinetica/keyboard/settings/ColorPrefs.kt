package com.kinetica.keyboard.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.kinetica.keyboard.ui.Hsv
import com.kinetica.keyboard.ui.KeyboardTheme

/**
 * The custom theme and trail colours as typed `#RRGGBB`, from the hue sliders they replace.
 */
object ColorPrefs {

    /** The retired slider drew a trail hue at this saturation and value. */
    private const val OLD_TRAIL_SAT = 0.85f
    private const val OLD_TRAIL_VAL = 1f
    private const val OLD_TRAIL_DEFAULT_HUE = 200

    /**
     * Turns stored hues into colours and removes them. The colour is the one the hue was drawn in,
     * so nothing changes on upgrade. Old keys win over a stored colour: present, they are either
     * not migrated yet or brought back by an old backup, so the order of the callers never matters.
     */
    @SuppressLint("ApplySharedPref") // the config read right after must see the colours
    fun migrate(prefs: SharedPreferences) {
        val stored = prefs.all
        val e = prefs.edit()
        var changed = false
        if (Prefs.THEME_HUE in stored || Prefs.THEME_COLOR in stored) {
            e.putString(Prefs.THEME_COLOR_HEX, HexColor.format(oldThemeColor(stored)))
                .remove(Prefs.THEME_HUE)
                .remove(Prefs.THEME_COLOR)
            changed = true
        }
        if (Prefs.TRAIL_COLOR_CUSTOM_HUE in stored) {
            val hue = (stored[Prefs.TRAIL_COLOR_CUSTOM_HUE] as? Int ?: OLD_TRAIL_DEFAULT_HUE).coerceIn(0, 360)
            e.putString(Prefs.TRAIL_COLOR_HEX, HexColor.format(oldTrailColor(hue)))
                .remove(Prefs.TRAIL_COLOR_CUSTOM_HUE)
            changed = true
        }
        if (changed) e.commit()
    }

    /**
     * The hue slider's colour: its hue when written, else the hue of the retired thirteen-colour
     * list's pick, as KeyboardConfig read them before.
     */
    private fun oldThemeColor(stored: Map<String, *>): Int {
        val hue = (stored[Prefs.THEME_HUE] as? Int)?.takeIf { it in 0..360 }?.toFloat()
            ?: Hsv.hueOf(
                HexColor.colorOr(
                    stored[Prefs.THEME_COLOR] as? String,
                    HexColor.colorOr(Prefs.DEFAULT_THEME_COLOR, 0),
                ),
            )
        return KeyboardTheme.primaryForHue(hue)
    }

    fun oldTrailColor(hue: Int): Int = Hsv.toColor(hue.toFloat(), OLD_TRAIL_SAT, OLD_TRAIL_VAL)
}
