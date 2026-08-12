package com.kinetica.keyboard.ui

/**
 * HSV to packed ARGB, and back far enough to recover a hue.
 *
 * Hand-rolled rather than calling `android.graphics.Color`, for the same reason
 * the DTW and the trie are hand-rolled: this is where the theme's whole palette
 * comes from, and every derived colour role should be checkable without a device.
 * `Color`'s static helpers are stubbed in the JVM unit-test runtime and throw
 * "not mocked", so a palette built on them cannot be tested at all - which is how
 * a near-white text constant on a near-white surface would have shipped.
 *
 * Deliberately reproduces `Color.HSVToColor`'s exact conventions, including the
 * odd one: a hue outside [0, 360) is treated as 0 rather than wrapped or clamped,
 * and channels round with +0.5. Matching it means the shipped palettes are
 * unchanged to the byte.
 */
object Hsv {

    /**
     * Opaque ARGB for [hue] degrees, [sat] and [value] in [0, 1].
     *
     * A non-finite or out-of-range hue collapses to 0 (red), which is what the
     * platform does and is a visible-but-harmless answer for a corrupt
     * preference - better than a transparent or black keyboard.
     */
    fun toColor(hue: Float, sat: Float, value: Float): Int {
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val hx = if (!hue.isFinite() || hue < 0f || hue >= 360f) 0f else hue / 60f
        val w = hx.toInt()
        val f = hx - w
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val r: Float
        val g: Float
        val b: Float
        when (w) {
            0 -> { r = v; g = t; b = p }
            1 -> { r = q; g = v; b = p }
            2 -> { r = p; g = v; b = t }
            3 -> { r = p; g = q; b = v }
            4 -> { r = t; g = p; b = v }
            else -> { r = v; g = p; b = q }
        }
        return argb(byteOf(r), byteOf(g), byteOf(b))
    }

    /** Hue in degrees [0, 360) of a packed colour; 0 for any grey. */
    fun hueOf(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d <= 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** Relative luminance (WCAG), for contrast checks on a derived palette. */
    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun byteOf(channel: Float): Int = (channel * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
