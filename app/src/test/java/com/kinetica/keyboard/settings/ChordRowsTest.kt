package com.kinetica.keyboard.settings

import com.kinetica.keyboard.data.ChordShortcut
import com.kinetica.keyboard.keys.EditorAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ChordRowsTest {

    private val rows = listOf(
        ChordShortcut(chord = "s", expansion = EditorAction.SELECT_ALL.output),
        ChordShortcut(chord = "a", expansion = "kind regards"),
        ChordShortcut(chord = "p", expansion = EditorAction.PASTE.output),
        ChordShortcut(chord = "m", expansion = "name@example.com"),
    )

    @Test
    fun sortsByKeyAscendingAndDescending() {
        assertEquals(
            listOf("a", "m", "p", "s"),
            ChordRows.sorted(rows, ChordRows.Sort.KEY_ASC).map { it.chord },
        )
        assertEquals(
            listOf("s", "p", "m", "a"),
            ChordRows.sorted(rows, ChordRows.Sort.KEY_DESC).map { it.chord },
        )
    }

    @Test
    fun sortsByFunctionThenKey() {
        // Text chords first, then the commands grouped by name rather than scattered
        // through the alphabet of their keys.
        assertEquals(
            listOf("a", "m", "p", "s"),
            ChordRows.sorted(rows, ChordRows.Sort.FUNCTION).map { it.chord },
        )
        val commandsFirst = listOf(
            ChordShortcut(chord = "b", expansion = EditorAction.SELECT_ALL.output),
            ChordShortcut(chord = "c", expansion = EditorAction.PASTE.output),
            ChordShortcut(chord = "z", expansion = "text"),
        )
        assertEquals(
            listOf("z", "c", "b"),
            ChordRows.sorted(commandsFirst, ChordRows.Sort.FUNCTION).map { it.chord },
        )
    }

    @Test
    fun aTextChordHasNoFunctionName() {
        assertEquals("", ChordRows.functionKey("kind regards"))
        assertEquals("PASTE", ChordRows.functionKey(EditorAction.PASTE.output))
    }

    @Test
    fun anUnknownActionSortsAsText() {
        // A typo in an expansion is not a command, and must not invent a sort group.
        assertEquals("", ChordRows.functionKey("action:pate"))
    }

    @Test
    fun theSortCycleReturnsToWhereItStarted() {
        var s = ChordRows.Sort.KEY_ASC
        repeat(3) { s = ChordRows.next(s) }
        assertEquals(ChordRows.Sort.KEY_ASC, s)
    }
}
