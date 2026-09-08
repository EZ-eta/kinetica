package com.kinetica.keyboard.settings

import com.kinetica.keyboard.data.ChordShortcut
import com.kinetica.keyboard.keys.EditorAction

/**
 * Ordering for the chord list.
 *
 * Pure so it can be tested: the screen around it is Android-only, but the question of what
 * order the rows come in is not. Asked for by a user, whose point was that one submenu
 * holding every chord setting stops being legible once the list is long.
 */
object ChordRows {

    enum class Sort { KEY_ASC, KEY_DESC, FUNCTION }

    /**
     * The next sort in the cycle, so one button can carry all three.
     */
    fun next(sort: Sort): Sort = when (sort) {
        Sort.KEY_ASC -> Sort.KEY_DESC
        Sort.KEY_DESC -> Sort.FUNCTION
        Sort.FUNCTION -> Sort.KEY_ASC
    }

    /**
     * What a row does, as a sort key: the action's own name, or "" for a text chord.
     *
     * Text sorts first because it is the common case and the one a user scans for; the
     * command chords then group together by name rather than being scattered through the
     * alphabet of their keys.
     */
    fun functionKey(expansion: String): String = EditorAction.of(expansion)?.name ?: ""

    /** [rows] in [sort] order. Key is always the tie-break, so the order is total. */
    fun sorted(rows: List<ChordShortcut>, sort: Sort): List<ChordShortcut> = when (sort) {
        Sort.KEY_ASC -> rows.sortedBy { it.chord }
        Sort.KEY_DESC -> rows.sortedByDescending { it.chord }
        Sort.FUNCTION -> rows.sortedWith(
            compareBy({ functionKey(it.expansion) }, { it.chord }),
        )
    }
}
