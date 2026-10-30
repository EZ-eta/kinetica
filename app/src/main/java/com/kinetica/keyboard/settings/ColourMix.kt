package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ui.Hsv

/**
 * The colour picker's state: one colour, seen as three sliders and a `#RRGGBB` field.
 *
 * The colour is the source of truth. A typed or tapped colour is kept exactly; the sliders show
 * it rounded. A grey has no hue and black no saturation, so those keep the sliders' last value
 * instead of jumping to red or to grey.
 *
 * No Android imports, so the sync rules are testable without a device.
 */
class ColourMix(initial: Int) {

    var color: Int = OPAQUE
        private set

    /** Degrees, [0, 360). */
    var hue = 0f
        private set

    /** [0, 1]. */
    var saturation = 0f
        private set

    /** [0, 1]. */
    var brightness = 0f
        private set

    init {
        takeColour(initial)
    }

    val hex: String
        get() = HexColor.format(color)

    /** Slider positions: hue in degrees, the other two in percent. */
    val hueStep: Int
        get() = Math.round(hue)
    val saturationStep: Int
        get() = Math.round(saturation * 100f)
    val brightnessStep: Int
        get() = Math.round(brightness * 100f)

    /**
     * Takes [raw] when it is a whole colour and returns true; anything else, a half-typed code
     * included, changes nothing and returns false, so the field is never rewritten under the user.
     */
    fun typed(raw: CharSequence?): Boolean {
        val r = HexColor.parse(raw) as? HexColor.Result.Valid ?: return false
        takeColour(r.color)
        return true
    }

    /** A swatch tapped. */
    fun choose(swatch: Int) = takeColour(swatch)

    fun moveHue(degrees: Int) {
        hue = (degrees.coerceIn(0, HUE_MAX) % HUE_MAX).toFloat()
        retint()
    }

    fun moveSaturation(percent: Int) {
        saturation = percent.coerceIn(0, 100) / 100f
        retint()
    }

    fun moveBrightness(percent: Int) {
        brightness = percent.coerceIn(0, 100) / 100f
        retint()
    }

    /** The hue track: the wheel at full saturation and brightness, red to red. */
    fun hueTrack(): IntArray = IntArray(HUE_STOPS) { i -> Hsv.toColor(i * 60f % 360f, 1f, 1f) }

    /** The saturation track: from this hue's grey to its fullest, at this brightness. */
    fun saturationTrack(): IntArray =
        intArrayOf(Hsv.toColor(hue, 0f, brightness), Hsv.toColor(hue, 1f, brightness))

    /** The brightness track: from black to this hue and saturation at full brightness. */
    fun brightnessTrack(): IntArray =
        intArrayOf(Hsv.toColor(hue, saturation, 0f), Hsv.toColor(hue, saturation, 1f))

    private fun takeColour(c: Int) {
        color = c or OPAQUE
        val v = Hsv.valueOf(color)
        val s = Hsv.saturationOf(color)
        brightness = v
        if (v > 0f) saturation = s
        if (v > 0f && s > 0f) hue = Hsv.hueOf(color)
    }

    private fun retint() {
        color = Hsv.toColor(hue, saturation, brightness)
    }

    companion object {
        const val HUE_MAX = 360

        /** Red, yellow, green, cyan, blue, magenta, red. */
        private const val HUE_STOPS = 7

        private const val OPAQUE = 0xFF shl 24

        /**
         * Two rows of eight: the shipped blue first, then a spread of hues muted enough for a
         * surface, then the neutrals a keyboard is often wanted in, dark to light.
         */
        val SWATCHES: IntArray = intArrayOf(
            0xFF475DFF.toInt(), // the shipped blue
            0xFF26B7FF.toInt(), // sky
            0xFF00897B.toInt(), // teal
            0xFF43A047.toInt(), // green
            0xFFF9A825.toInt(), // amber
            0xFFFB8C00.toInt(), // orange
            0xFFE53935.toInt(), // red
            0xFFD81B60.toInt(), // pink
            0xFF8E24AA.toInt(), // purple
            0xFF5E35B1.toInt(), // violet
            0xFF6D4C41.toInt(), // brown
            0xFF546E7A.toInt(), // slate
            0xFF202124.toInt(), // near black
            0xFF616161.toInt(), // dark grey
            0xFF9E9E9E.toInt(), // grey
            0xFFECEFF1.toInt(), // near white
        )
    }
}
