package com.kinetica.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keyboard reaches into a short-edge cutout, so a phone held sideways keeps no margin on the
 * camera side only; the keys clear the hole by an inset on both sides, and only when the hole
 * meets them. Pixel 7 sideways: a 2400 x 1080 screen, the hole on the left edge mid-height.
 */
class CutoutPadTest {

    private val viewW = 2400f
    private val viewH = 500f

    @Test
    fun noCutoutCostsNothing() {
        assertEquals(0f, LayoutTransforms.cutoutPadPx(FloatArray(0), viewW, viewH), 0f)
    }

    @Test
    fun aHoleAboveTheKeysCostsNothing() {
        // The usual case: the hole sits in the app above the keyboard.
        val hole = floatArrayOf(0f, -320f, 120f, -200f)
        assertEquals(0f, LayoutTransforms.cutoutPadPx(hole, viewW, viewH), 0f)
    }

    @Test
    fun aHoleOnTheLeftThatMeetsTheKeysInsetsBothSides() {
        val hole = floatArrayOf(0f, 40f, 120f, 160f)
        assertEquals(120f, LayoutTransforms.cutoutPadPx(hole, viewW, viewH), 1e-3f)
    }

    @Test
    fun aHoleOnTheRightCountsFromTheRightEdge() {
        // 270 degrees: the same phone, the hole on the other edge.
        val hole = floatArrayOf(viewW - 120f, 40f, viewW, 160f)
        assertEquals(120f, LayoutTransforms.cutoutPadPx(hole, viewW, viewH), 1e-3f)
    }

    @Test
    fun theWiderOfTwoHolesSetsTheInset() {
        val holes = floatArrayOf(0f, 0f, 90f, 100f, viewW - 140f, 300f, viewW, 400f)
        assertEquals(140f, LayoutTransforms.cutoutPadPx(holes, viewW, viewH), 1e-3f)
    }

    @Test
    fun aHoleInTheMiddleIsNotASideInset() {
        val hole = floatArrayOf(1150f, 0f, 1250f, 80f)
        assertEquals(0f, LayoutTransforms.cutoutPadPx(hole, viewW, viewH), 0f)
    }

    @Test
    fun theInsetKeepsTheSideMarginCeiling() {
        val hole = floatArrayOf(0f, 0f, 590f, 100f)
        assertEquals(viewW * 0.15f, LayoutTransforms.cutoutPadPx(hole, viewW, viewH), 1e-3f)
    }
}
