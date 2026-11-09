package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A space on a symbol page goes back to the letters, under its switch (#53). */
class LettersAfterSpaceTest {

    @Test
    fun aSpaceOnEitherSymbolPageBringsTheLetters() {
        assertTrue(lettersAfterSpace(enabled = true, board = "symbols"))
        assertTrue(lettersAfterSpace(enabled = true, board = "symbols2"))
    }

    @Test
    fun theNumberPadAndTheEditingPageStay() {
        // A phone number or a run of moves goes on past a space.
        assertFalse(lettersAfterSpace(enabled = true, board = "numpad"))
        assertFalse(lettersAfterSpace(enabled = true, board = EditPage.NAME))
        // On the letters already: nothing to rebuild.
        assertFalse(lettersAfterSpace(enabled = true, board = "qwerty_it"))
        assertFalse(lettersAfterSpace(enabled = true, board = null))
    }

    @Test
    fun theSwitchOffKeepsThePage() {
        assertFalse(lettersAfterSpace(enabled = false, board = "symbols"))
        assertFalse(lettersAfterSpace(enabled = false, board = "symbols2"))
    }

    @Test
    fun theSpaceKeyAsksAfterItsSpace() {
        // The service has no JVM reach: the space is written first, then the page changes.
        val src = ImeSources.declaring("KeyType.SPACE -> {").text
        val branch = src.substringAfter("KeyType.SPACE -> {").substringBefore("KeyType.BACKSPACE ->")
        assertTrue(branch.indexOf("onSpace()") in 0 until branch.indexOf("lettersAfterSpace("))
        assertTrue(branch.contains("lettersAfterSpace(config.lettersAfterSpace, keyboardView?.shownBoard)"))
        assertTrue(branch.contains("setKeyboardLayout(views.alphaLayout())"))
    }
}
