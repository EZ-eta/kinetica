package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.content.ContextCompat
import com.kinetica.keyboard.R
import kotlin.math.min

/**
 * Resolved color roles for every keyboard surface. Three sources:
 *  - "default": the bundled palette in colors.xml (current look)
 *  - "dynamic": Android 12+ Material You system palette, read straight from
 *    android.R.color.system_* resources (no Material Components dependency -
 *    the keyboard is a Canvas surface, not a themed view hierarchy)
 *  - "custom": every role derived from one user-picked primary color via
 *    fixed HSV transforms (Material-style tone roles, dark-surface oriented)
 * Purely static colors: zen mode's animation gating is untouched by theming.
 */
class KeyboardTheme(
    val background: Int,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val keyText: Int,
    val keyHint: Int,
    val suggestionBg: Int,
    val suggestionText: Int,
    val suggestionPrimary: Int,
    val chip: Int,
    val popupBg: Int,
    val accent: Int,
) {
    /** Trail hue matching the theme accent, for the "theme" trail option. */
    val accentHue: Float
        get() {
            val hsv = FloatArray(3)
            Color.colorToHSV(accent, hsv)
            return hsv[0]
        }

    companion object {
        const val MODE_DEFAULT = "default"
        const val MODE_DYNAMIC = "dynamic"
        const val MODE_CUSTOM = "custom"

        fun resolve(context: Context, mode: String, customPrimary: Int): KeyboardTheme =
            when {
                mode == MODE_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    fromDynamic(context)
                mode == MODE_CUSTOM -> fromPrimary(customPrimary)
                else -> fromResources(context)
            }

        fun fromResources(context: Context): KeyboardTheme {
            fun c(id: Int) = ContextCompat.getColor(context, id)
            return KeyboardTheme(
                background = c(R.color.kbd_background),
                key = c(R.color.kbd_key),
                keySpecial = c(R.color.kbd_key_special),
                keyPressed = c(R.color.kbd_key_pressed),
                keyText = c(R.color.kbd_key_text),
                keyHint = c(R.color.kbd_key_hint),
                suggestionBg = c(R.color.suggestion_bar_bg),
                suggestionText = c(R.color.suggestion_text),
                suggestionPrimary = c(R.color.suggestion_text_primary),
                chip = c(R.color.correction_chip),
                popupBg = c(R.color.popup_bg),
                accent = c(R.color.popup_selected),
            )
        }

        private fun fromDynamic(context: Context): KeyboardTheme {
            fun c(id: Int) = ContextCompat.getColor(context, id)
            // Dark-surface mapping of the wallpaper-derived tonal palettes:
            // neutral1 carries surfaces, neutral2 carries muted text, accent1
            // carries the highlight roles.
            return KeyboardTheme(
                background = c(android.R.color.system_neutral1_900),
                key = c(android.R.color.system_neutral1_800),
                keySpecial = c(android.R.color.system_neutral2_800),
                keyPressed = c(android.R.color.system_neutral1_600),
                keyText = c(android.R.color.system_neutral1_50),
                keyHint = c(android.R.color.system_neutral2_200),
                suggestionBg = c(android.R.color.system_neutral2_900),
                suggestionText = c(android.R.color.system_neutral1_100),
                suggestionPrimary = c(android.R.color.system_neutral1_10),
                chip = c(android.R.color.system_accent2_700),
                popupBg = c(android.R.color.system_neutral1_700),
                accent = c(android.R.color.system_accent1_300),
            )
        }

        /**
         * One primary color spreads across the role set by pinning value
         * (brightness) per role and damping saturation on large surfaces so
         * a screaming primary still yields readable dark surfaces.
         */
        private fun fromPrimary(primary: Int): KeyboardTheme {
            val hsv = FloatArray(3)
            Color.colorToHSV(primary, hsv)
            val h = hsv[0]
            val s = hsv[1]
            fun tone(sat: Float, value: Float): Int =
                Color.HSVToColor(floatArrayOf(h, sat, value))
            val surfaceSat = min(s, 0.55f)
            return KeyboardTheme(
                background = tone(surfaceSat * 0.55f, 0.10f),
                key = tone(surfaceSat * 0.50f, 0.21f),
                keySpecial = tone(surfaceSat * 0.50f, 0.16f),
                keyPressed = tone(min(s, 0.65f), 0.35f),
                keyText = Color.rgb(0xED, 0xED, 0xF2),
                keyHint = tone(0.15f, 0.66f),
                suggestionBg = tone(surfaceSat * 0.55f, 0.13f),
                suggestionText = Color.rgb(0xD8, 0xD8, 0xE0),
                suggestionPrimary = Color.WHITE,
                chip = tone(min(s, 0.60f), 0.30f),
                popupBg = tone(surfaceSat * 0.50f, 0.27f),
                accent = primary,
            )
        }
    }
}
