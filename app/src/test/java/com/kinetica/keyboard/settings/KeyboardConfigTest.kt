package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure enter-alternates parser. `parseEnterAlternates` is a pure
 * String? -> List<String> function, so it is JVM-testable directly without
 * SharedPreferences.
 */
class KeyboardConfigTest {

    private val default = listOf("?", "!", ",")

    @Test
    fun blankOrNullFallsBackToDefault() {
        assertEquals(default, KeyboardConfig.parseEnterAlternates(null))
        assertEquals(default, KeyboardConfig.parseEnterAlternates(""))
        assertEquals(default, KeyboardConfig.parseEnterAlternates("   "))
    }

    @Test
    fun parsesTheDefaultString() {
        assertEquals(default, KeyboardConfig.parseEnterAlternates("? ! ,"))
    }

    @Test
    fun parsesCustomSymbolsAndTrimsWhitespace() {
        assertEquals(listOf(";", ".", "?"), KeyboardConfig.parseEnterAlternates("  ;   .   ?  "))
    }

    @Test
    fun capsAtThreeSymbols() {
        assertEquals(listOf("(", ")", ":"), KeyboardConfig.parseEnterAlternates("( ) : ; ."))
    }

    @Test
    fun fewerThanThreeSymbolsKept() {
        assertEquals(listOf(":"), KeyboardConfig.parseEnterAlternates(":"))
        assertEquals(listOf("(", ")"), KeyboardConfig.parseEnterAlternates("( )"))
    }
}
