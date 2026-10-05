package com.kinetica.keyboard.settings

import com.kinetica.keyboard.settings.ShortcutKeyInput.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** The trigger key, typed into a one-character field instead of picked from a list. */
class ShortcutKeyInputTest {

    @Test
    fun oneKeyOfAnyBoard() {
        assertEquals(Result.One('a'), ShortcutKeyInput.parse("a"))
        assertEquals(Result.One('й'), ShortcutKeyInput.parse("й"))
        assertEquals(Result.One('1'), ShortcutKeyInput.parse("1"))
        assertEquals(Result.One(','), ShortcutKeyInput.parse(","))
    }

    @Test
    fun spacesAreDroppedAndALetterIsItsLowercase() {
        assertEquals(Result.One('v'), ShortcutKeyInput.parse("  V "))
        assertEquals(Result.One('ж'), ShortcutKeyInput.parse("\tЖ\n"))
    }

    @Test
    fun nothingAndMoreThanOneAreRefused() {
        assertEquals(Result.Blank, ShortcutKeyInput.parse(""))
        assertEquals(Result.Blank, ShortcutKeyInput.parse("   "))
        assertEquals(Result.Blank, ShortcutKeyInput.parse(null))
        assertEquals(Result.TooMany("ab"), ShortcutKeyInput.parse("a b"))
        assertEquals(Result.TooMany(":)"), ShortcutKeyInput.parse(":)"))
    }
}
