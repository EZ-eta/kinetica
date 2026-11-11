package com.kinetica.keyboard.keys

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutTransforms
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The editing page (#49): up to [MAX_ROWS] rows of keys, each one text, a key combination or an
 * action, above a bottom row no edit reaches (letters, space, backspace, enter), so an edited page
 * always keeps its way back. The default is a D-pad with selection, the clipboard and Ctrl.
 *
 * Stored as text, not JSON, so the rules here run on the JVM: each key percent-encoded, keys split
 * by `,` and rows by `|`, neither of which the encoder writes.
 */
data class EditPage(val rows: List<List<String>>) {

    /** The stored text; a page with no rows is one separator, so it still reads as a change. */
    fun serialize(): String =
        if (rows.isEmpty()) ROW_SEP else rows.joinToString(ROW_SEP) { row -> row.joinToString(KEY_SEP) { encode(it) } }

    /**
     * The page as a board: the rows share the height evenly with the fixed row below them, and a
     * row's keys share its width, so a row of three is as easy to hit as the D-pad's arrows.
     */
    fun layout(): KeyboardLayout {
        val h = 1f / (rows.size + 1)
        val keys = ArrayList<Key>()
        for ((r, row) in rows.withIndex()) {
            val w = 1f / row.size
            for ((c, out) in row.withIndex()) {
                keys.add(Key("edit_${r + 1}_${c + 1}", KeyType.CHAR, ChordLabels.of(out), out, c * w, r * h, w, h))
            }
        }
        val y = rows.size * h
        // `mode`, the id of `?123`, so the slide that opened the page closes it from the same corner.
        keys.add(Key("mode", KeyType.MODE_ALPHA, "ABC", "", 0f, y, SIDE_W, h))
        keys.add(Key("space", KeyType.SPACE, " ", "", SIDE_W, y, 1f - 3 * SIDE_W, h))
        keys.add(Key("backspace", KeyType.BACKSPACE, "⌫", "", 1f - 2 * SIDE_W, y, SIDE_W, h))
        keys.add(Key("enter", KeyType.ENTER, "⏎", "", 1f - SIDE_W, y, SIDE_W, h))
        return KeyboardLayout(NAME, "", keys)
    }

    /** [row] with [output] added at its end, or null when the row is full. */
    fun withAdded(row: Int, output: String): EditPage? {
        if (row == rows.size && rows.size < MAX_ROWS) return EditPage(rows + listOf(listOf(output)))
        val keys = rows.getOrNull(row) ?: return null
        if (keys.size >= MAX_KEYS) return null
        return EditPage(rows.mapIndexed { i, k -> if (i == row) k + output else k })
    }

    /** The key at [row], [col] set to [output]. */
    fun withKey(row: Int, col: Int, output: String): EditPage =
        EditPage(rows.mapIndexed { i, k -> if (i == row) k.mapIndexed { j, o -> if (j == col) output else o } else k })

    /** The key at [row], [col] gone; a row left empty goes with it. */
    fun without(row: Int, col: Int): EditPage =
        EditPage(rows.mapIndexed { i, k -> if (i == row) k.filterIndexed { j, _ -> j != col } else k }.filter { it.isNotEmpty() })

    /** The key at [row], [col] moved one place along its row, [by] -1 or +1; unchanged at an end. */
    fun moved(row: Int, col: Int, by: Int): EditPage {
        val keys = rows.getOrNull(row) ?: return this
        val to = col + by
        if (col !in keys.indices || to !in keys.indices) return this
        val swapped = keys.toMutableList().also { it[col] = keys[to]; it[to] = keys[col] }
        return EditPage(rows.mapIndexed { i, k -> if (i == row) swapped else k })
    }

    /** Why a key may not hold an output ([problem]). */
    enum class Problem { EMPTY, LETTER, THIS_PAGE }

    companion object {
        /** The board's name, as the view reports the board on screen. */
        const val NAME = LayoutTransforms.EDIT_PAGE

        const val MAX_ROWS = 4
        const val MAX_KEYS = 6

        /** The width of each fixed key beside the space. */
        private const val SIDE_W = 0.15f

        private const val ROW_SEP = "|"
        private const val KEY_SEP = ","

        val DEFAULT = EditPage(
            listOf(
                // Ctrl last: the next key goes out with it, as Ctrl+Home or Ctrl+Z.
                listOf(
                    EditorAction.SELECT_MODE.output, EditorAction.SELECT_ALL.output, EditorAction.COPY.output,
                    EditorAction.PASTE.output, EditorAction.CTRL_NEXT.output,
                ),
                // Tab first, so up sits over down: both the middle of a row of five.
                listOf(EditorAction.TAB.output, EditorAction.HOME.output, EditorAction.ARROW_UP.output, EditorAction.END.output, EditorAction.PAGE_UP.output),
                listOf(
                    "combo:ctrl+left", EditorAction.ARROW_LEFT.output, EditorAction.ARROW_DOWN.output,
                    EditorAction.ARROW_RIGHT.output, "combo:ctrl+right",
                ),
                listOf(EditorAction.UNDO.output, EditorAction.REDO.output, EditorAction.CUT.output, EditorAction.FORWARD_DELETE.output, EditorAction.PAGE_DOWN.output),
            ),
        )

        /**
         * The stored page; absent is the default. Keys past the limits and keys a page may not hold
         * are dropped, an emptied row with them; text that does not decode is the default.
         */
        fun parse(text: String?): EditPage {
            if (text == null) return DEFAULT
            if (text.isEmpty()) return EditPage(emptyList())
            return try {
                EditPage(
                    text.split(ROW_SEP)
                        .map { row -> row.split(KEY_SEP).filter { it.isNotEmpty() }.map { decode(it) }.filter { problem(it) == null }.take(MAX_KEYS) }
                        .filter { it.isNotEmpty() }
                        .take(MAX_ROWS),
                )
            } catch (e: IllegalArgumentException) {
                DEFAULT
            }
        }

        /**
         * Why a key may not hold [output], or null. A lone letter would make the page a letters page
         * and turn swiping on; the page's own action on one of its keys would only close it.
         */
        fun problem(output: String): Problem? = when {
            output.isEmpty() -> Problem.EMPTY
            output.length == 1 && Alphabet.LATIN.isLetter(output[0].lowercaseChar()) -> Problem.LETTER
            output == EditorAction.EDIT_PAGE.output -> Problem.THIS_PAGE
            else -> null
        }

        /**
         * Whether a key with [output] repeats while held, at backspace's pace: the moves of one
         * character or one word, and the delete. Home, End and the pages would only send the same
         * place again.
         */
        fun repeats(output: String): Boolean {
            EditorAction.of(output)?.let { return it in REPEATING }
            val combo = KeyCombo.parse(output) ?: return false
            return combo.key in REPEATING_KEYS
        }

        private val REPEATING = setOf(
            EditorAction.ARROW_LEFT, EditorAction.ARROW_RIGHT, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN,
            EditorAction.FORWARD_DELETE,
        )
        private val REPEATING_KEYS = setOf("left", "right", "up", "down", "delete")

        private fun encode(output: String): String = URLEncoder.encode(output, "UTF-8")

        private fun decode(text: String): String = URLDecoder.decode(text, "UTF-8")
    }
}
