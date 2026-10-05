package com.kinetica.keyboard.settings

import com.kinetica.keyboard.data.ChordShortcut
import com.kinetica.keyboard.keys.ChordKey
import com.kinetica.keyboard.keys.ChordTrigger
import com.kinetica.keyboard.keys.EditorAction

/**
 * Ordering for the chord list.
 *
 * Pure so it can be tested; the screen around it is Android-only. A long chord list needs an order
 * to stay legible.
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
     * Text sorts first because it is the common case and the one a user scans for; the command
     * chords then group by name instead of scattering through the alphabet of their keys.
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

    /**
     * [rows] in groups by the key they are held with, `?123` first, each group in [sort] order;
     * an empty group is left out. A row this build cannot read stays visible under `?123`, so it
     * can still be deleted.
     */
    fun grouped(rows: List<ChordShortcut>, sort: Sort): List<Pair<ChordTrigger, List<ChordShortcut>>> {
        val by = rows.groupBy { ChordKey.decode(it.chord)?.trigger ?: ChordTrigger.MODE }
        return ChordTrigger.entries.mapNotNull { t -> by[t]?.let { t to sorted(it, sort) } }
    }

    /** The key a row shows: the tapped key alone, whatever it is held with. */
    fun keyLabel(row: ChordShortcut): String = ChordKey.decode(row.chord)?.key?.toString() ?: row.chord
}
