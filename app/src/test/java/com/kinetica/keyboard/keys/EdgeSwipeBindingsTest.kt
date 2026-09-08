package com.kinetica.keyboard.keys

import com.kinetica.keyboard.keys.EdgeSwipeBinding.Direction
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The implicit alternate-swipe layer synthesized from a layout.
 * Hand-builds [KeyboardLayout]/[Key] directly - the JVM test runtime stubs
 * org.json, so LayoutLoader/parse must never be touched here.
 */
class EdgeSwipeBindingsTest {

    private fun charKey(
        id: String,
        y: Float,
        alternates: List<String>,
    ) = Key(
        id = id, type = KeyType.CHAR, label = id, output = id,
        x = 0f, y = y, w = 0.1f, h = 0.25f, alternates = alternates,
    )

    /** Mirrors qwerty.json geometry and alternates for the keys under test. */
    private fun layout(): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            // Top letter row (y = 0.00): digits, vowels list accents first.
            charKey("q", 0.00f, listOf("1")),
            charKey("e", 0.00f, listOf("è", "é", "ê", "ë", "ē", "3")),
            charKey("u", 0.00f, listOf("ù", "ú", "û", "ü", "ū", "7")),
            charKey("p", 0.00f, listOf("0")),
            // A key whose only alternate is a letter. It draws that letter, so it types it.
            charKey("w", 0.00f, listOf("ω")),
            // A key with no alternates at all draws nothing and stays unbound.
            charKey("k", 0.00f, emptyList()),
            // Home row (y = 0.25): never bound.
            charKey("a", 0.25f, listOf("à", "@")),
            // Bottom letter row (y = 0.50): symbols.
            charKey("z", 0.50f, listOf("ž", "ź", "ż", "'")),
            charKey("c", 0.50f, listOf("ç", "ć", ";")),
            charKey("v", 0.50f, listOf(":")),
            charKey("n", 0.50f, listOf("ñ", "ń", "!")),
            charKey("m", 0.50f, listOf("?")),
            // Non-letter keys sharing the bottom row are excluded by isLetter.
            Key("shift", KeyType.SHIFT, "⇧", "", 0f, 0.50f, 0.15f, 0.25f),
            Key("enter", KeyType.ENTER, "⏎", "", 0f, 0.75f, 0.15f, 0.25f),
        ),
    )

    private val empty = EdgeSwipeBindings(emptyList())

    @Test
    fun topRowUpYieldsTheHintItShows() {
        val b = EdgeSwipeBindings.withImplicitAlternates(layout(), empty)
        assertEquals("1", b.outputFor("q", Direction.UP))
        assertEquals("0", b.outputFor("p", Direction.UP))
        // Vowels list accents before the digit; the predicate skips the accents.
        // A vowel listing accents first DRAWS the accent, so that is what it types. Turning
        // on "Prioritize numbers over accents" moves the hint and the swipe together.
        assertEquals("è", b.outputFor("e", Direction.UP))
        assertEquals("ù", b.outputFor("u", Direction.UP))
        // Top-row keys get no DOWN binding.
        assertNull(b.outputFor("q", Direction.DOWN))
    }

    @Test
    fun bottomRowDownYieldsTheHintItShows() {
        // The report this answers: the swipe used to insert the first NON-LETTER alternate
        // while the key drew its first alternate, so "z" showed ž and typed \'.
        val b = EdgeSwipeBindings.withImplicitAlternates(layout(), empty)
        assertEquals("ž", b.outputFor("z", Direction.DOWN))
        assertEquals("ç", b.outputFor("c", Direction.DOWN))
        assertEquals("ñ", b.outputFor("n", Direction.DOWN))
        assertEquals("?", b.outputFor("m", Direction.DOWN))
        assertEquals(":", b.outputFor("v", Direction.DOWN))
        // Bottom-row keys get no UP binding.
        assertNull(b.outputFor("z", Direction.UP))
    }

    @Test
    fun homeRowAndNonLetterKeysAndAltlessKeysAreUnbound() {
        val b = EdgeSwipeBindings.withImplicitAlternates(layout(), empty)
        assertNull(b.outputFor("a", Direction.UP))
        assertNull(b.outputFor("a", Direction.DOWN))
        assertNull(b.outputFor("shift", Direction.DOWN))
        assertNull(b.outputFor("enter", Direction.UP))
        // Top-row key whose only alternate is a letter yields nothing.
        // No alternates at all means no hint and nothing to type.
        assertNull(b.outputFor("k", Direction.UP))
        // But a letter hint is still a hint: what it draws is what it types.
        assertEquals("ω", b.outputFor("w", Direction.UP))
    }

    @Test
    fun explicitBindingsShadowImplicitOnes() {
        // A binding the USER set still wins over the hint. No shipped default does any more.
        val mine = EdgeSwipeBindings(
            listOf(EdgeSwipeBinding("z", Direction.DOWN, "@")),
        )
        val b = EdgeSwipeBindings.withImplicitAlternates(layout(), mine)
        assertEquals("@", b.outputFor("z", Direction.DOWN))
        // Keys the user has not bound keep the glyph they show.
        assertEquals("ç", b.outputFor("c", Direction.DOWN))
        assertEquals("1", b.outputFor("q", Direction.UP))
    }

    @Test
    fun theShippedDefaultsNoLongerContradictAKeysLabel() {
        // v drew ":" and typed ",", b drew "/" and typed ".", x drew a quote and opened the
        // emoji picker. All three are gone so the rule has no exceptions to explain; the
        // picker keeps its comma long-press route.
        val d = EdgeSwipeBindings.DEFAULTS
        assertNull(d.outputFor("v", Direction.DOWN))
        assertNull(d.outputFor("b", Direction.DOWN))
        assertNull(d.outputFor("x", Direction.DOWN))
        assertEquals("!", d.outputFor("backspace", Direction.UP))
        assertEquals("?", d.outputFor("enter", Direction.UP))
    }

    @Test
    fun theEmojiGestureIsNoLongerADefault() {
        assertTrue(
            "no shipped default may open the picker",
            EdgeSwipeBindings.DEFAULTS.bindings.none { it.output == EdgeSwipeBindings.ACTION_EMOJI },
        )
    }

    @Test
    fun spanishNKeyYieldsTheAccentItDraws() {
        // qwerty_es "n" = ["ñ","!","¡"] draws ñ, so it types ñ. A Spanish writer swiping
        // down on n wants the letter far more often than the bang, and turning on
        // "Prioritize numbers over accents" moves the hint and the swipe together.
        val es = KeyboardLayout(
            name = "qwerty_es", locale = "es_ES",
            keys = listOf(charKey("n", 0.50f, listOf("ñ", "!", "¡"))),
        )
        val b = EdgeSwipeBindings.withImplicitAlternates(es, empty)
        assertEquals("ñ", b.outputFor("n", Direction.DOWN))
    }
    // ---- collision warnings -------------------------------------------------

    private fun shadow(key: String, dir: EdgeSwipeBinding.Direction) =
        EdgeSwipeBindings.shadowedGesture(key, dir)

    @Test
    fun everyShippedDefaultIsSilent() {
        // The property that makes the warning worth showing at all: it must never
        // fire on a binding the app ships, or it is noise from the first launch.
        for (b in EdgeSwipeBindings.DEFAULTS.bindings) {
            assertNull(
                "default ${b.keyId}/${b.direction} flagged as a conflict",
                shadow(b.keyId, b.direction),
            )
        }
    }

    @Test
    fun aHorizontalBindingOnALetterShadowsShortTypingSwipes() {
        // How the engine reads a two-letter word is a short sideways swipe, so a
        // left/right binding on a letter competes with typing itself.
        assertEquals(EdgeSwipeBindings.SHADOWS_TYPING_SWIPE,
            shadow("a", EdgeSwipeBinding.Direction.LEFT))
        assertEquals(EdgeSwipeBindings.SHADOWS_TYPING_SWIPE,
            shadow("z", EdgeSwipeBinding.Direction.RIGHT))
        // Vertical on a letter is the implicit digit/symbol layer and is fine.
        assertNull(shadow("a", EdgeSwipeBinding.Direction.UP))
        assertNull(shadow("v", EdgeSwipeBinding.Direction.DOWN))
    }

    @Test
    fun theSpecialKeysShadowTheirOwnSlides() {
        assertEquals(EdgeSwipeBindings.SHADOWS_CURSOR_SLIDE,
            shadow("space", EdgeSwipeBinding.Direction.LEFT))
        assertEquals(EdgeSwipeBindings.SHADOWS_STAGED_DELETE,
            shadow("backspace", EdgeSwipeBinding.Direction.LEFT))
        assertEquals(EdgeSwipeBindings.SHADOWS_LAYER_SLIDE,
            shadow("mode", EdgeSwipeBinding.Direction.RIGHT))
        assertEquals(EdgeSwipeBindings.SHADOWS_ENTER_POPUP,
            shadow("enter", EdgeSwipeBinding.Direction.LEFT))
    }

    @Test
    fun enterUpIsNotFlaggedBecauseItIsTheSameAnswerAsThePopup() {
        // The shipped default binds enter-up to "?" and the popup's primary is
        // "?" as well, deliberately. Flagging it would call the app's own design
        // a conflict.
        assertNull(shadow("enter", EdgeSwipeBinding.Direction.UP))
        assertNull(shadow("backspace", EdgeSwipeBinding.Direction.UP))
    }

    @Test
    fun anUnknownKeyIsNotGuessedAt() {
        // Multi-character ids that are not the special keys, and anything from a
        // layout this build does not know about.
        assertNull(shadow("apostrophe", EdgeSwipeBinding.Direction.LEFT))
        assertNull(shadow("comma", EdgeSwipeBinding.Direction.RIGHT))
        assertNull(shadow("", EdgeSwipeBinding.Direction.LEFT))
    }

}
