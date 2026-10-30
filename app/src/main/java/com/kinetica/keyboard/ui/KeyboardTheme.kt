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
 * Two independent choices, not one list of named themes. Where the colors come from:
 *  - "default": the bundled palette in colors.xml
 *  - "dynamic": the Android 12+ Material You palette, read from android.R.color.system_*
 *    (no Material Components dependency: the keyboard is a Canvas surface, not themed views)
 *  - "custom": every role derived from one user-typed colour by fixed HSV transforms
 *
 * and, separately, light or dark (the BRIGHTNESS_* values). Crossing the two gives "Material
 * You but light" and "my own hue but light" without a named theme for every pair.
 *
 * Static colors only: zen mode's animation gating is untouched by theming.
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
         * Saturation and value the retired hue slider rendered its hue at; kept to migrate a stored
         * hue to the same colour.
         */
        private const val CUSTOM_SAT = 0.72f
        private const val CUSTOM_VAL = 1.0f

        /**
         * How far a dark typed colour dims the surfaces. Black takes them to 60% of their
         * brightness; any lower and the keys sink into the background.
         */
        private const val DARK_VALUE_REACH = 0.4f

        /**
         * The same on the light table, held smaller: down to black, body text stays above 4.5:1 on
         * the dimmed keys and bar.
         */
        private const val LIGHT_VALUE_REACH = 0.2f

        /**
         * The dark table's accent is the typed colour itself, unless it is too dark to show on the
         * keys: then the same hue and saturation at this value.
         */
        private const val ACCENT_MIN_VALUE = 0.6f

        // The dark table's three text roles, as literals so no android.graphics.Color call
        // reaches the JVM test runtime, where its statics throw "not mocked".
        private const val DARK_KEY_TEXT = 0xFFEDEDF2.toInt()
        private const val DARK_SUGGESTION_TEXT = 0xFFD8D8E0.toInt()
        private const val DARK_SUGGESTION_PRIMARY = 0xFFFFFFFF.toInt()

        /** Hue of a stored colour, for migrating the retired colour list. */
        fun hueOf(color: Int): Float = Hsv.hueOf(color)

        /** A hue from the retired slider as a primary color for [fromPrimary]. */
        fun primaryForHue(hue: Float): Int = Hsv.toColor(hue, CUSTOM_SAT, CUSTOM_VAL)

        /**
         * True when the typed colour has any effect in [mode]. The bundled and Material You
         * palettes ignore it, and a preview that does not say so looks broken.
         */
        fun colourAffects(mode: String): Boolean = mode == MODE_CUSTOM

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
            // Explicit *_light ids, not a values-night split: the bundled look stays dark for
            // anyone who never opens this setting, which a night-qualified resource would
            // reverse on every phone in light mode.
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
            // One role mapping read off opposite ends of the wallpaper-derived tonal ramps:
            // neutral1 for surfaces, neutral2 for muted text, accent1 for the highlight. Light
            // takes a darker accent tone (600 against 300) or it vanishes into a pale surface.
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
         * One primary color spreads across the role set by pinning value (brightness) per role
         * and damping saturation on large surfaces, so a loud primary still yields readable
         * surfaces. The primary's own value dims the surfaces within a bounded reach; at full
         * value the palette is the one the hue slider gave, so a migrated colour does not move.
         *
         * Two tone tables, one per brightness. The dark table keeps fixed near-white text
         * constants so existing custom dark themes do not shift; the light table derives its
         * text, since near-white on a near-white surface would be unreadable.
         */
        fun fromPrimary(primary: Int, light: Boolean = false): KeyboardTheme {
            val h = Hsv.hueOf(primary)
            val s = Hsv.saturationOf(primary)
            val v = Hsv.valueOf(primary)
            fun tone(sat: Float, value: Float): Int = Hsv.toColor(h, sat, value)
            val surfaceSat = min(s, 0.55f)
            val dim = 1f - (if (light) LIGHT_VALUE_REACH else DARK_VALUE_REACH) * (1f - v)
            return if (light) {
                KeyboardTheme(
                    // Surfaces near white, tinted enough to read as the hue. Keys sit brighter
                    // than the background so the gaps read as the darker line, as in the dark
                    // palette.
                    background = tone(surfaceSat * 0.22f, 0.90f * dim),
                    key = tone(surfaceSat * 0.10f, 1.00f * dim),
                    keySpecial = tone(surfaceSat * 0.20f, 0.94f * dim),
                    keyPressed = tone(min(s, 0.40f), 0.82f * dim),
                    keyText = tone(min(s, 0.30f), 0.16f),
                    keyHint = tone(min(s, 0.35f), 0.45f),
                    suggestionBg = tone(surfaceSat * 0.18f, 0.96f * dim),
                    suggestionText = tone(min(s, 0.30f), 0.26f),
                    suggestionPrimary = tone(min(s, 0.40f), 0.12f),
                    chip = tone(min(s, 0.45f), 0.86f * dim),
                    popupBg = tone(surfaceSat * 0.12f, 1.00f * dim),
                    accent = tone(min(s, 0.85f), 0.70f),
                )
            } else {
                KeyboardTheme(
                    background = tone(surfaceSat * 0.55f, 0.10f * dim),
                    key = tone(surfaceSat * 0.50f, 0.21f * dim),
                    keySpecial = tone(surfaceSat * 0.50f, 0.16f * dim),
                    keyPressed = tone(min(s, 0.65f), 0.35f * dim),
                    keyText = DARK_KEY_TEXT,
                    keyHint = tone(0.15f, 0.66f),
                    suggestionBg = tone(surfaceSat * 0.55f, 0.13f * dim),
                    suggestionText = DARK_SUGGESTION_TEXT,
                    suggestionPrimary = DARK_SUGGESTION_PRIMARY,
                    chip = tone(min(s, 0.60f), 0.30f * dim),
                    popupBg = tone(surfaceSat * 0.50f, 0.27f * dim),
                    accent = if (v >= ACCENT_MIN_VALUE) primary else tone(s, ACCENT_MIN_VALUE),
                )
            }
        }
    }
}
