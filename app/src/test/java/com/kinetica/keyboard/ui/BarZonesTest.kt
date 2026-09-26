package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar cut itself into five equal zones whatever the words were, so anything long was
 * ellipsized. The report: "three or four suggestions looked the same with ellipses and
 * differed in word endings while only their beginnings were shown."
 *
 * Widths here are already the full allowance a word needs, the way the view computes it.
 * A bar 320 units wide is roughly a phone's word area in dp.
 */
class BarZonesTest {

    private val bar = 320f

    private fun sizes(vararg widths: Float) = BarZones.pages(widths.toList(), bar, 5)

    @Test
    fun shortWordsStillFillTheRow() {
        // The case that must not change: five short candidates, one page, as before.
        assertEquals(listOf(5), sizes(50f, 50f, 50f, 50f, 50f))
    }

    @Test
    fun longWordsTakeFewerZonesInsteadOfAnEllipsis() {
        // 110 each: three would need 330 of 320, so two share and the third pages.
        assertEquals(listOf(2, 2), sizes(110f, 110f, 110f, 110f))
    }

    @Test
    fun theWidestWordOnAPageDecidesTheWholePage() {
        // Zones stay equal within a page, so a short word beside a long one does not let
        // a third in: the page is sized by its widest member, not its total. These three
        // sum to 230 of 320 and still need two pages.
        assertEquals(listOf(2, 1), sizes(20f, 150f, 60f))
    }

    @Test
    fun aWordThatCannotShareGetsItsOwnPage() {
        assertEquals(listOf(1, 3), sizes(300f, 80f, 80f, 80f))
    }

    @Test
    fun aWordWiderThanTheBarIsStillGivenAPage() {
        // Nothing can hold it, so it pages alone and fit() ellipsizes it there. The list
        // must still be complete: every word belongs to exactly one page.
        val s = sizes(400f, 60f, 60f)
        assertEquals(listOf(1, 2), s)
        assertEquals(3, s.sum())
    }

    @Test
    fun theCeilingIsStillFive() {
        assertEquals(listOf(5, 5), BarZones.pages(List(10) { 1f }, bar, 5))
    }

    @Test
    fun everyWordLandsOnExactlyOnePage() {
        // The invariant the view depends on: startOfPage walks these sizes to index into
        // the full list, so a partition that lost or duplicated a word would mis-tap.
        val widths = listOf(40f, 210f, 90f, 90f, 300f, 15f, 15f, 120f, 120f, 55f)
        for (available in listOf(0f, 100f, 200f, 320f, 640f)) {
            val s = BarZones.pages(widths, available, 5)
            assertEquals("available=$available", widths.size, s.sum())
            assertTrue("available=$available", s.all { it in 1..5 })
            assertEquals(0, BarZones.startOfPage(s, 0))
            assertEquals(widths.size, BarZones.startOfPage(s, s.size))
        }
    }

    @Test
    fun anUnlaidOutBarFallsBackToTheFixedFive() {
        // width is 0 before layout, so there is nothing to pack against. One page per
        // word would be the wrong answer to "I do not know yet".
        assertEquals(listOf(5, 5, 1), BarZones.pages(List(11) { 200f }, 0f, 5))
    }

    @Test
    fun anEmptyListHasNoPages() {
        assertEquals(emptyList<Int>(), BarZones.pages(emptyList(), bar, 5))
    }
}
