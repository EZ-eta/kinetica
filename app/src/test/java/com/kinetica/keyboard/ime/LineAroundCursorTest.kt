package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line COPY_LINE puts on the clipboard (#19: "copy whole line").
 *
 * Read either side of the cursor in one call, with no selection and no remembered offset, so
 * a remembered offset cannot eat text. A line that runs past the read is refused, never cut.
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
