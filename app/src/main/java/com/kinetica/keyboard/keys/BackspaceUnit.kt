package com.kinetica.keyboard.keys

/**
 * What one unit of a backspace slide is: a character or a word (a piece, with punctuation stops).
 *
 * A held shift swaps the setting (Prefs.SHIFT_SWAPS_SLIDES) until the first unit is staged; from
 * there the mode is fixed for the touch, since a flip would change what the staged count means.
 * Pure, so the rule is testable without the controller's Handler.
 */
class BackspaceUnit {

    /** The setting: single characters instead of whole words. */
    var charMode = false

    /** A staged word stops at punctuation, so a link goes a piece at a time. Ignored for characters. */
    var stopsAtPunctuation = false

    /** A held shift, with its switch on. */
    var flipped = false

    private var fixedChars: Boolean? = null

    /** Whether a unit is a character, now: the fixed mode once a unit was staged. */
    val chars: Boolean get() = fixedChars ?: (charMode != flipped)

    val granularity: DeleteSpan.Granularity
        get() = when {
            chars -> DeleteSpan.Granularity.CHARS
            stopsAtPunctuation -> DeleteSpan.Granularity.PIECES
            else -> DeleteSpan.Granularity.WORDS
        }

    /** The first unit was staged: the mode holds until [release]. */
    fun fix() {
        if (fixedChars == null) fixedChars = chars
    }

    /** The touch ended: the next one reads the setting and shift again. */
    fun release() {
        fixedChars = null
    }
}
