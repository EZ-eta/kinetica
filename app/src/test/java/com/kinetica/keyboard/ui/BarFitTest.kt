package com.kinetica.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a bar word too wide for its place is shortened. Every letter measures one unit here, so a
 * width is a letter count, the ellipsis included.
 */
class BarFitTest {

    private val units: (String) -> Float = { it.codePointCount(0, it.length).toFloat() }

    private fun fit(word: String, width: Float, middle: Boolean) = BarFit.fit(word, width, middle, units)

    @Test
    fun aWordThatFitsIsShownWhole() {
        assertEquals("organizzazione", fit("organizzazione", 14f, middle = false))
        assertEquals("organizzazione", fit("organizzazione", 14f, middle = true))
    }

    @Test
    fun theEndCutKeepsTheStartAsTheBarAlwaysDid() {
        assertEquals("organiz…", fit("organizzazione", 8f, middle = false))
        assertEquals("org…", fit("organizzazione", 1f, middle = false))
    }

    /** `organizzare` and `organizzazione` both read `organiz…` cut at the end; their endings tell them apart. */
    @Test
    fun theMiddleCutKeepsTheStartAndTheEnd() {
        val cut = fit("organizzazione", 12f, middle = true)
        assertEquals("organi…zione", cut)
        assertTrue(fit("organizzare", 8f, middle = true).endsWith("are"))
        assertTrue(fit("organizzazione", 8f, middle = true).endsWith("one"))
    }

    @Test
    fun theMiddleCutKeepsTwoLettersASideHoweverNarrow() {
        assertEquals("or…ne", fit("organizzazione", 1f, middle = true))
    }

    @Test
    fun aLetterOutsideTheBmpIsNeverSplit() {
        // Seven mathematical letters, each a surrogate pair.
        assertEquals("𝔞𝔟…𝔣𝔤", fit("𝔞𝔟𝔠𝔡𝔢𝔣𝔤", 5f, middle = true))
    }
}
