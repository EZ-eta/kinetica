package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the suggestion bar packs words into pages. Five fixed zones ellipsized long words until
 * candidates differing only in their endings looked the same.
 *
 * Widths here are already the full allowance a word needs, as the view computes it.
 * A bar 320 units wide is roughly a phone's word area in dp.
 */
class BarZonesTest {

    private val bar = 320f

    private fun sizes(vararg widths: Float) = BarZones.pages(widths.toList(), bar, 5)

    @Test
    fun shortWordsStillFillTheRow() {
        // Five short candidates still share one page.
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
        // Nothing can hold it, so it pages alone and fit ellipsizes it there. The list
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
        // The width is 0 before layout, so there is nothing to pack against; the fixed five is a
        // better guess than one page per word.
        assertEquals(listOf(5, 5, 1), BarZones.pages(List(11) { 200f }, 0f, 5))
    }

    @Test
    fun anEmptyListHasNoPages() {
        assertEquals(emptyList<Int>(), BarZones.pages(emptyList(), bar, 5))
    }

    @Test
    fun compactSpacingFitsMoreWordsOnAPage() {
        // Equal zones give three short words and one long one 4 x 88 = 352 > 320: two pages.
        val widths = listOf(40f, 50f, 45f, 88f)
        assertEquals(listOf(3, 1), BarZones.pages(widths, bar, 5))
        // Side by side with a 12-unit gap they take 259 of 320: one page.
        assertEquals(listOf(4), BarZones.compactPages(widths, bar, 12f, 5))
        assertEquals(listOf(5, 1), BarZones.compactPages(List(6) { 40f }, bar, 12f, 5))
    }

    @Test
    fun compactWordsSitAGapApartAndEveryTouchLandsOnOne() {
        val widths = listOf(40f, 60f, 50f)
        val gap = 12f
        val l = BarZones.compactLayout(widths, bar, gap)
        assertEquals(0f, l.bounds.first(), 0f)
        assertEquals(bar, l.bounds.last(), 0f)
        for (i in 0 until widths.size - 1) {
            assertEquals(widths[i] / 2f + gap + widths[i + 1] / 2f, l.centers[i + 1] - l.centers[i], 1e-4f)
            // The boundary sits in the middle of the gap between the two words.
            assertEquals(l.centers[i] + widths[i] / 2f + gap / 2f, l.bounds[i + 1], 1e-4f)
        }
        // The group is centred on the bar.
        assertEquals(bar - (l.centers.last() + widths.last() / 2f), l.centers.first() - widths.first() / 2f, 1e-4f)
        assertEquals(0, BarZones.zoneOf(l, 0f))
        assertEquals(1, BarZones.zoneOf(l, l.centers[1]))
        assertEquals(2, BarZones.zoneOf(l, bar - 1f))
    }

    @Test
    fun equalZonesAreTheOldArithmetic() {
        val l = BarZones.equalLayout(4, bar)
        assertEquals(80f, l.bounds[1], 0f)
        assertEquals(200f, l.centers[2], 0f)
        assertEquals(3, BarZones.zoneOf(l, 319f))
    }
}
