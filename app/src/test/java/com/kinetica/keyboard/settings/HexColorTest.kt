package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** A colour typed as `#RRGGBB`: what is taken, what is refused, and how it is written back. */
class HexColorTest {

    @Test
    fun aSixDigitColourInAnyCaseWithOrWithoutTheHash() {
        val green = HexColor.Result.Valid(0xFF2E7D32.toInt())
        assertEquals(green, HexColor.parse("#2E7D32"))
        assertEquals(green, HexColor.parse("#2e7d32"))
        assertEquals(green, HexColor.parse("2E7D32"))
    }

    @Test
    fun spacesAreDropped() {
        assertEquals(HexColor.Result.Valid(0xFF2E7D32.toInt()), HexColor.parse(" # 2E 7D 32 "))
    }

    @Test
    fun nothingTypedIsBlank() {
        assertEquals(HexColor.Result.Blank, HexColor.parse(""))
        assertEquals(HexColor.Result.Blank, HexColor.parse("   "))
        assertEquals(HexColor.Result.Blank, HexColor.parse(null))
    }

    @Test
    fun theWrongLengthOrABadDigitIsRefused() {
        for (typed in listOf("#12345", "#1234567", "#GG0000", "#12 34 5Z", "##123456", "#FFF")) {
            assertEquals(typed, HexColor.Result.Invalid(typed.filterNot { it.isWhitespace() }), HexColor.parse(typed))
        }
    }

    @Test
    fun aColourIsWrittenBackAsSixUpperCaseDigits() {
        assertEquals("#2E7D32", HexColor.format(0xFF2E7D32.toInt()))
        assertEquals("#00000A", HexColor.format(0x0000000A))
        assertEquals("#808080", HexColor.format(0x80808080.toInt()))
    }

    @Test
    fun anythingButAColourFallsBack() {
        assertEquals(7, HexColor.colorOr("teal", 7))
        assertEquals(7, HexColor.colorOr(null, 7))
        assertEquals(0xFF000000.toInt(), HexColor.colorOr("#000000", 7))
    }
}
