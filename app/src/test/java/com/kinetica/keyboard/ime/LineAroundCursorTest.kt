package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line COPY_LINE puts on the clipboard (#19: "copy whole line").
 *
 * Read out of the editor either side of the cursor in the same call, with no selection made
 * and no remembered offset used, which is what keeps item 69's class of mistake out of it.
 * A line that runs past the read is refused, never cut.
 */
class LineAroundCursorTest {

    private val read = 50

    @Test
    fun theLineIsBothSidesOfTheCursorUpToTheNewlines() {
        assertEquals("hello", lineAroundCursor("first\nhel", "", "lo\nthird", read))
        assertEquals("only line", lineAroundCursor("only ", "", "line", read))
    }

    @Test
    fun aSelectionInsideTheLineIsPartOfIt() {
        assertEquals("hello", lineAroundCursor("a\nh", "ell", "o\nb", read))
        assertNull(lineAroundCursor("a\nh", "ell\nx", "o", read))
    }

    @Test
    fun aLineRunningPastTheReadIsRefused() {
        val full = "x".repeat(read)
        assertNull(lineAroundCursor(full, "", "end", read))
        assertNull(lineAroundCursor("start", "", full, read))
        // A newline inside the read means the line's end was seen.
        assertEquals("y", lineAroundCursor("x".repeat(read - 2) + "\ny", "", "", read))
    }

    @Test
    fun anEmptyLineIsEmpty() {
        assertEquals("", lineAroundCursor("a\n", "", "\nb", read))
    }
}
