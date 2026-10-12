package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `?123` menu drawn on the spacebar while chord labels are on: on the bar, a thumb's width
 * per cell, and inside the view however many cells the menu holds.
 */
class StripOverKeyTest {

    @Test
    fun aFewCellsShareTheSpacebar() {
        // 1080px at 2.75: a spacebar half the view holds four cells of its own width.
        val density = 2.75f
        val cell = stripCellOver(540f, 4, 48f * density, 1080f, 4f * density)
        assertEquals(135f, cell, 0.01f)
        assertEquals(270f, popupLeft(270f, cell * 4, 1080f, 4f * density), 0f)
    }

    @Test
    fun aLongMenuKeepsThumbCellsInsideTheView() {
        for (px in 600..1600 step 13) {
            for (dpi in 260..640 step 20) {
                val density = dpi / 160f
                val viewW = px.toFloat()
                val margin = 4f * density
                val spaceLeft = viewW * 0.25f
                for (n in 1..12) {
                    val cell = stripCellOver(viewW * 0.5f, n, 48f * density, viewW, margin)
                    val left = popupLeft(spaceLeft, cell * n, viewW, margin)
                    val fits = 48f * density * n <= viewW - 2f * margin
                    if (fits) assertTrue("px=$px dpi=$dpi n=$n cell=$cell", cell >= 48f * density - 0.01f)
                    assertTrue("px=$px dpi=$dpi n=$n left=$left", left >= margin - 0.01f)
                    assertTrue("px=$px dpi=$dpi n=$n", left + cell * n <= viewW - margin + 0.01f)
                }
            }
        }
    }
}
