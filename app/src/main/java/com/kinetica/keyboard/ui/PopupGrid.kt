/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.ui

import kotlin.math.abs
import kotlin.math.floor

/**
 * Where each item of a long-press grid sits (#8). Row 0 is the top row.
 *
 * Item 0 is the key's own letter. In a [rising] grid it sits in the bottom row under the thumb,
 * so sliding back to the start cancels a long-press fired by mistake and nothing wanted hides
 * under the thumb. Item 1, the plain-lift default and the corner character, sits straight above
 * it; the rest fill the bottom row nearest the letter first, then each row above. The width is
 * the user's choice and overflow adds a row. The grid is trimmed to its filled cells, so no
 * empty row or column is drawn.
 */
class PopupGrid private constructor(
    val cols: Int,
    val rows: Int,
    private val items: IntArray,
) {

    /** The item in a cell, or -1 for an empty cell or one outside the grid. */
    fun itemAt(col: Int, row: Int): Int =
        if (col in 0 until cols && row in 0 until rows) items[row * cols + col] else -1

    fun colOf(item: Int): Int = items.indexOf(item).let { if (it < 0) -1 else it % cols }

    fun rowOf(item: Int): Int = items.indexOf(item).let { if (it < 0) -1 else it / cols }

    /**
     * The item under a finger at ([x], [y]) for a grid drawn from ([left], [top]) in cells of
     * [cellW] x [cellH]. A finger past an edge reads the edge cell, as in the single-row strip.
     */
    fun itemUnder(x: Float, y: Float, left: Float, top: Float, cellW: Float, cellH: Float): Int {
        if (cellW <= 0f || cellH <= 0f) return -1
        val col = floor((x - left) / cellW).toInt().coerceIn(0, cols - 1)
        val row = floor((y - top) / cellH).toInt().coerceIn(0, rows - 1)
        return itemAt(col, row)
    }

    companion object {
        /**
         * [count] items, the letter first, in a grid [width] cells wide rising from the letter
         * at column [letterCol]. See the class comment for the order.
         */
        fun rising(count: Int, width: Int, letterCol: Int): PopupGrid {
            require(count >= 1) { "no grid of $count cells: the key's letter is one" }
            require(width >= 1 && letterCol in 0 until width) { "letter column $letterCol outside a grid $width wide" }
            if (count == 1) return PopupGrid(1, 1, intArrayOf(0))
            val rows = maxOf(2, (count + width - 1) / width)
            val items = IntArray(width * rows) { -1 }
            val bottom = rows - 1
            items[bottom * width + letterCol] = 0
            items[(bottom - 1) * width + letterCol] = 1
            val centre = (width - 1) / 2f
            // Nearest the letter first; a tie goes to the column nearer the grid's middle, then
            // left, which reads `ò o ó` in the order the list gives them.
            val byNearness = (0 until width).sortedWith(
                compareBy<Int>({ abs(it - letterCol) }, { abs(it - centre) }, { it }),
            )
            var next = 2
            for (row in bottom downTo 0) {
                for (col in byNearness) {
                    if (next >= count) break
                    val cell = row * width + col
                    if (items[cell] == -1) items[cell] = next++
                }
            }
            return trimmed(width, rows, items)
        }

        /**
         * The single-row strip past the width it can hold: [perRow] items a row, in list order,
         * the first row at the bottom nearest the key and the rest stacked above it.
         */
        fun wrapped(count: Int, perRow: Int): PopupGrid {
            require(count >= 1 && perRow >= 1) { "count $count and perRow $perRow must both be 1 or more" }
            val cols = minOf(count, perRow)
            val rows = (count + perRow - 1) / perRow
            val items = IntArray(cols * rows) { -1 }
            for (i in 0 until count) {
                val row = rows - 1 - i / perRow
                items[row * cols + i % perRow] = i
            }
            return PopupGrid(cols, rows, items)
        }

        /**
         * The column the letter takes in a grid [width] cells wide, for a key centred at
         * [keyCenterX] in a view [viewW] wide. The middle, or for an even width the side that
         * faces the screen's middle, moved toward the edge only as far as keeps the grid on screen
         * with the letter still over the key.
         */
        fun letterColumn(width: Int, keyCenterX: Float, cellW: Float, viewW: Float, margin: Float): Int {
            if (width <= 1 || cellW <= 0f) return 0
            val preferred = if (width % 2 == 1) {
                width / 2
            } else if (keyCenterX < viewW / 2f) {
                width / 2 - 1
            } else {
                width / 2
            }
            val maxByLeft = floor((keyCenterX - margin) / cellW - 0.5f).toInt()
            val minByRight = kotlin.math.ceil(width - 0.5f - (viewW - margin - keyCenterX) / cellW).toInt()
            return preferred.coerceAtMost(maxByLeft).coerceAtLeast(minByRight).coerceIn(0, width - 1)
        }

        /** [items] cut down to the columns and rows that hold something. */
        private fun trimmed(width: Int, rows: Int, items: IntArray): PopupGrid {
            var c0 = width
            var c1 = -1
            var r0 = rows
            var r1 = -1
            for (i in items.indices) {
                if (items[i] < 0) continue
                val c = i % width
                val r = i / width
                if (c < c0) c0 = c
                if (c > c1) c1 = c
                if (r < r0) r0 = r
                if (r > r1) r1 = r
            }
            val cols = c1 - c0 + 1
            val rs = r1 - r0 + 1
            val out = IntArray(cols * rs) { -1 }
            for (r in 0 until rs) {
                for (c in 0 until cols) out[r * cols + c] = items[(r + r0) * width + c + c0]
            }
            return PopupGrid(cols, rs, out)
        }
    }
}
