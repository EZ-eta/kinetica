package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press grid's shape and hit-test (#8). Pure, so the whole selection model is checked
 * here; drawing is `KeyboardView` and is checked on a device.
 *
 * The shapes and the letter on the bottom row under the thumb are a design choice: a UI
 * rule, not a decode golden.
 */
class PopupGridTest {

    private fun rows(g: PopupGrid): List<List<Int>> =
        (0 until g.rows).map { r -> (0 until g.cols).map { c -> g.itemAt(c, r) } }

    @Test
    fun theLetterSitsInTheBottomRowWithTheDefaultStraightAboveIt() {
        for (width in 3..5) {
            for (n in 2..25) {
                for (col in 0 until width) {
                    val g = PopupGrid.rising(n, width, col)
                    val tag = "width=$width n=$n col=$col"
                    assertEquals("$tag letter row", g.rows - 1, g.rowOf(0))
                    assertEquals("$tag default column", g.colOf(0), g.colOf(1))
                    assertEquals("$tag default row", g.rowOf(0) - 1, g.rowOf(1))
                    val placed = rows(g).flatten().filter { it >= 0 }.sorted()
                    assertEquals("$tag every item once", (0 until n).toList(), placed)
                    // Trimmed: no row and no column is empty, which looked bad in 5 x 3.
                    for (r in rows(g)) assertTrue("$tag empty row", r.any { it >= 0 })
                    for (c in 0 until g.cols) {
                        assertTrue("$tag empty column", (0 until g.rows).any { g.itemAt(c, it) >= 0 })
                    }
                    assertTrue("$tag wider than chosen", g.cols <= width)
                }
            }
        }
    }

    @Test
    fun italianOIsThePyramidTheDeveloperChose() {
        // `o 9 ò ó ô ö õ` in 3 x 3.
        val g = PopupGrid.rising(7, 3, 1)
        assertEquals(listOf(listOf(-1, 6, -1), listOf(4, 1, 5), listOf(2, 0, 3)), rows(g))
    }

    @Test
    fun moreThanTheShapeHoldsAddsARowAndKeepsTheWidth() {
        // English `o` holds 10.
        assertEquals(3 to 4, PopupGrid.rising(10, 3, 1).let { it.cols to it.rows })
        assertEquals(4 to 3, PopupGrid.rising(10, 4, 1).let { it.cols to it.rows })
        assertEquals(5 to 2, PopupGrid.rising(10, 5, 2).let { it.cols to it.rows })
        // A letter with one alternate is two cells, one above the other.
        assertEquals(1 to 2, PopupGrid.rising(2, 3, 1).let { it.cols to it.rows })
        assertEquals(1 to 1, PopupGrid.rising(1, 3, 1).let { it.cols to it.rows })
    }

    @Test
    fun anEvenWidthPutsItsWiderSideTowardTheMiddleOfTheScreen() {
        val cell = 100f
        val view = 1000f
        assertEquals(1, PopupGrid.letterColumn(4, 300f, cell, view, 4f))
        assertEquals(2, PopupGrid.letterColumn(4, 700f, cell, view, 4f))
        assertEquals(1, PopupGrid.letterColumn(3, 500f, cell, view, 4f))
        assertEquals(2, PopupGrid.letterColumn(5, 500f, cell, view, 4f))
    }

    @Test
    fun aKeyAtTheEdgeKeepsItsLetterOverTheKey() {
        // `q` and `p`: the grid would hang off the screen, so the letter takes the edge column
        // and the grid stays over the thumb.
        val cell = 100f
        val view = 1000f
        for (width in 3..5) {
            assertEquals("width=$width left", 0, PopupGrid.letterColumn(width, 60f, cell, view, 4f))
            assertEquals("width=$width right", width - 1, PopupGrid.letterColumn(width, 940f, cell, view, 4f))
        }
        val g = PopupGrid.rising(7, 3, 0)
        assertEquals(0, g.colOf(0))
        assertEquals(0, g.colOf(1))
    }

    @Test
    fun theSingleRowWrapsPastNineWithTheFirstRowNearestTheKey() {
        // Polish `a` holds 11.
        val g = PopupGrid.wrapped(11, 9)
        assertEquals(9 to 2, g.cols to g.rows)
        assertEquals((0 until 9).toList(), rows(g)[1])
        assertEquals(listOf(9, 10) + List(7) { -1 }, rows(g)[0])
        assertEquals(9 to 1, PopupGrid.wrapped(9, 9).let { it.cols to it.rows })
        assertEquals(5 to 1, PopupGrid.wrapped(5, 9).let { it.cols to it.rows })
    }

    private val cell = 100f
    private val left = 40f
    private val top = -80f

    private fun under(g: PopupGrid, col: Float, row: Float): Int =
        g.itemUnder(left + col * cell, top + row * cell, left, top, cell, cell)

    @Test
    fun aFingerReadsTheCellItIsOver() {
        val g = PopupGrid.rising(10, 4, 1)
        // The thumb opens the grid on the letter: that is the cancel.
        assertEquals(0, under(g, g.colOf(0) + 0.5f, g.rowOf(0) + 0.5f))
        assertEquals(1, under(g, g.colOf(0) + 0.5f, g.rowOf(0) - 0.5f))
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                assertEquals("cell $c,$r", g.itemAt(c, r), under(g, c + 0.01f, r + 0.01f))
            }
        }
    }

    @Test
    fun aFingerPastAnEdgeReadsTheEdgeCell() {
        val g = PopupGrid.rising(9, 3, 1)
        assertEquals(g.itemAt(0, 0), under(g, -3f, -3f))
        assertEquals(g.itemAt(g.cols - 1, g.rows - 1), under(g, 9f, 9f))
        // Below the grid is the key row: the bottom row, as the strip reads it.
        assertEquals(0, under(g, g.colOf(0) + 0.5f, g.rows + 2f))
    }

    @Test
    fun anEmptyCellSelectsNothing() {
        val g = PopupGrid.rising(7, 3, 1)
        assertEquals(-1, under(g, 0.5f, 0.5f))
        assertEquals(-1, under(g, 2.5f, 0.5f))
        assertEquals(-1, g.itemUnder(0f, 0f, 0f, 0f, 0f, cell))
    }
}
