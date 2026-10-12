package com.kinetica.keyboard.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long-press strip wide enough to have its cells capped never crashes the keyboard. The capped
 * strip leaves exactly two margins, and float rounding can put the right bound under the left,
 * which made `coerceIn` throw on some screen widths and densities.
 */
class PopupLeftTest {

    @Test
    fun aCappedStripStaysInsideTheViewAtEveryWidthAndDensity() {
        var capped = 0
        for (px in 600..1600 step 7) {
            for (dpi in 260..640 step 3) {
                val density = dpi / 160f
                val viewW = px.toFloat()
                val margin = 4f * density
                for (n in 5..12) {
                    val keyW = viewW / 10f
                    val cellW = maxOf(keyW, 48f * density).coerceAtMost((viewW - 8f * density) / n)
                    val w = cellW * n
                    if (cellW < maxOf(keyW, 48f * density)) capped++
                    for (raw in listOf(-50f, 0f, viewW / 2f - w / 2f, viewW)) {
                        val left = popupLeft(raw, w, viewW, margin)
                        assertTrue("px=$px dpi=$dpi n=$n left=$left", left >= margin && left <= viewW)
                    }
                }
            }
        }
        assertTrue("no capped strip was tried", capped > 0)
    }
}
