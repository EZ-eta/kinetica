package com.kinetica.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat
import com.kinetica.keyboard.R
import kotlin.math.min

/**
 * Resolved color roles for every keyboard surface.
 *
 * Two independent choices, deliberately kept orthogonal rather than folded into
 * one list of named themes. WHERE the colors come from:
 *  - "default": the bundled palette in colors.xml
 *  - "dynamic": Android 12+ Material You system palette, read straight from
 *    android.R.color.system_* resources (no Material Components dependency -
 *    the keyboard is a Canvas surface, not a themed view hierarchy)
 *  - "custom": every role derived from one user-picked hue via fixed HSV
 *    transforms
 * and independently, whether they land LIGHT or DARK (the BRIGHTNESS_* values).
 *
 * Crossing the two is what makes "Material You but light" and "my own hue but
 * light" expressible; a flat list of named themes would need six entries to say
 * the same thing and nine as soon as a third source appears.
 *
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
        get() = Hsv.hueOf(accent)

    companion object {
        const val MODE_DEFAULT = "default"
        const val MODE_DYNAMIC = "dynamic"
        const val MODE_CUSTOM = "custom"

        const val BRIGHTNESS_DARK = "dark"
        const val BRIGHTNESS_LIGHT = "light"
        const val BRIGHTNESS_SYSTEM = "system"

        /**
         * Saturation and value the custom hue is rendered at. The hue is the only
         * thing the user picks: a full HSV picker is three sliders to get wrong,
         * and the roles below re-derive saturation per surface anyway, so the two
         * extra degrees of freedom bought nothing.
         */
        private const val CUSTOM_SAT = 0.72f
        private const val CUSTOM_VAL = 1.0f

        // The dark table's three text roles, unchanged from the first ship and
        // written as literals so no android.graphics.Color call reaches the JVM
        // test runtime, where every one of its statics throws "not mocked".
        private const val DARK_KEY_TEXT = 0xFFEDEDF2.toInt()
        private const val DARK_SUGGESTION_TEXT = 0xFFD8D8E0.toInt()
        private const val DARK_SUGGESTION_PRIMARY = 0xFFFFFFFF.toInt()

        /** HSV saturation of a packed colour; the companion to [Hsv.hueOf]. */
        private fun saturationOf(color: Int): Float {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val max = maxOf(r, g, b)
            if (max <= 0f) return 0f
            return (max - minOf(r, g, b)) / max
        }

        /** Hue of a stored colour, for migrating the retired colour list. */
        fun hueOf(color: Int): Float = Hsv.hueOf(color)

        /** A user-chosen hue as a primary color for [fromPrimary]. */
        fun primaryForHue(hue: Float): Int = Hsv.toColor(hue, CUSTOM_SAT, CUSTOM_VAL)

        /**
         * True when the accent-hue setting has any effect in [mode]. The bundled
         * and Material You palettes ignore it, so a settings preview that moved
         * with the slider in those modes would be lying about what it does.
         */
        fun hueAffects(mode: String): Boolean = mode == MODE_CUSTOM

        /** True when [brightness] resolves to a light palette in this context. */
        fun isLight(context: Context, brightness: String): Boolean = when (brightness) {
            BRIGHTNESS_LIGHT -> true
            BRIGHTNESS_SYSTEM -> systemIsLight(context)
            else -> false
        }

        private fun systemIsLight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                Configuration.UI_MODE_NIGHT_YES

        fun resolve(
            context: Context,
            mode: String,
            customPrimary: Int,
            brightness: String = BRIGHTNESS_DARK,
        ): KeyboardTheme {
            val light = isLight(context, brightness)
            return when {
                mode == MODE_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    fromDynamic(context, light)
                mode == MODE_CUSTOM -> fromPrimary(customPrimary, light)
                else -> fromResources(context, light)
            }
        }

        fun fromResources(context: Context, light: Boolean = false): KeyboardTheme {
            fun c(id: Int) = ContextCompat.getColor(context, id)
            // Explicit *_light ids rather than a values-night split: the bundled
            // look is dark and must stay dark for anyone who never opens this
            // setting, which a night-qualified resource would silently reverse
            // for every user whose phone is in light mode.
            return if (light) {
                KeyboardTheme(
                    background = c(R.color.kbd_background_light),
                    key = c(R.color.kbd_key_light),
                    keySpecial = c(R.color.kbd_key_special_light),
                    keyPressed = c(R.color.kbd_key_pressed_light),
                    keyText = c(R.color.kbd_key_text_light),
                    keyHint = c(R.color.kbd_key_hint_light),
                    suggestionBg = c(R.color.suggestion_bar_bg_light),
                    suggestionText = c(R.color.suggestion_text_light),
                    suggestionPrimary = c(R.color.suggestion_text_primary_light),
                    chip = c(R.color.correction_chip_light),
                    popupBg = c(R.color.popup_bg_light),
                    accent = c(R.color.popup_selected),
                )
            } else {
                KeyboardTheme(
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
        }

        private fun fromDynamic(context: Context, light: Boolean): KeyboardTheme {
            fun c(id: Int) = ContextCompat.getColor(context, id)
            // Same role mapping read off opposite ends of the wallpaper-derived
            // tonal ramps: neutral1 carries surfaces, neutral2 muted text,
            // accent1 the highlight. Light needs a DARKER accent tone than dark
            // does (300 -> 600) or the highlight vanishes into a pale surface.
            return if (light) {
                KeyboardTheme(
                    background = c(android.R.color.system_neutral1_50),
                    key = c(android.R.color.system_neutral1_10),
                    keySpecial = c(android.R.color.system_neutral2_100),
                    keyPressed = c(android.R.color.system_neutral1_300),
                    keyText = c(android.R.color.system_neutral1_900),
                    keyHint = c(android.R.color.system_neutral2_700),
                    suggestionBg = c(android.R.color.system_neutral2_50),
                    suggestionText = c(android.R.color.system_neutral1_800),
                    suggestionPrimary = c(android.R.color.system_neutral1_900),
                    chip = c(android.R.color.system_accent2_100),
                    popupBg = c(android.R.color.system_neutral1_100),
                    accent = c(android.R.color.system_accent1_600),
                )
            } else {
                KeyboardTheme(
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
        }

        /**
         * One primary color spreads across the role set by pinning value
         * (brightness) per role and damping saturation on large surfaces so a
         * screaming primary still yields readable surfaces.
         *
         * Two tone tables, one per brightness. The dark table is UNCHANGED,
         * hard-coded near-white text constants included: deriving those from the
         * hue would have been tidier and would have shifted every existing custom
         * dark theme by a few units for no benefit anyone asked for. The light
         * table has to derive them - a near-white constant on a near-white
         * surface is the one thing that would make a light custom theme unusable.
         */
        fun fromPrimary(primary: Int, light: Boolean = false): KeyboardTheme {
            val h = Hsv.hueOf(primary)
            val s = saturationOf(primary)
            fun tone(sat: Float, value: Float): Int = Hsv.toColor(h, sat, value)
            val surfaceSat = min(s, 0.55f)
            return if (light) {
                KeyboardTheme(
                    // Surfaces near white, tinted just enough to read as the
                    // chosen hue; keys sit BRIGHTER than the background so the
                    // gaps between them read as the darker line, which is the
                    // relationship the dark palette has rather than its inverse.
                    background = tone(surfaceSat * 0.22f, 0.90f),
                    key = tone(surfaceSat * 0.10f, 1.00f),
                    keySpecial = tone(surfaceSat * 0.20f, 0.94f),
                    keyPressed = tone(min(s, 0.40f), 0.82f),
                    keyText = tone(min(s, 0.30f), 0.16f),
                    keyHint = tone(min(s, 0.35f), 0.45f),
                    suggestionBg = tone(surfaceSat * 0.18f, 0.96f),
                    suggestionText = tone(min(s, 0.30f), 0.26f),
                    suggestionPrimary = tone(min(s, 0.40f), 0.12f),
                    chip = tone(min(s, 0.45f), 0.86f),
                    popupBg = tone(surfaceSat * 0.12f, 1.00f),
                    accent = tone(min(s, 0.85f), 0.70f),
                )
            } else {
                KeyboardTheme(
                    background = tone(surfaceSat * 0.55f, 0.10f),
                    key = tone(surfaceSat * 0.50f, 0.21f),
                    keySpecial = tone(surfaceSat * 0.50f, 0.16f),
                    keyPressed = tone(min(s, 0.65f), 0.35f),
                    keyText = DARK_KEY_TEXT,
                    keyHint = tone(0.15f, 0.66f),
                    suggestionBg = tone(surfaceSat * 0.55f, 0.13f),
                    suggestionText = DARK_SUGGESTION_TEXT,
                    suggestionPrimary = DARK_SUGGESTION_PRIMARY,
                    chip = tone(min(s, 0.60f), 0.30f),
                    popupBg = tone(surfaceSat * 0.50f, 0.27f),
                    accent = primary,
                )
            }
        }
    }
}
