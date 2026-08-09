package com.kinetica.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enter alternate injection. Hand-builds [KeyboardLayout]/[Key]
 * directly - the JVM test runtime stubs org.json, so LayoutLoader/parse must
 * never be touched here.
 */
class LayoutMutationsTest {

    private fun layout(): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("q", KeyType.CHAR, "q", "q", 0f, 0.0f, 0.1f, 0.25f, alternates = listOf("1")),
            Key("comma", KeyType.CHAR, ",", ",", 0.15f, 0.75f, 0.1f, 0.25f),
            Key("enter", KeyType.ENTER, "⏎", "", 0.85f, 0.75f, 0.15f, 0.25f),
        ),
    )

    @Test
    fun withEnterAlternatesSetsThePopupCells() {
        val out = LayoutMutations.withEnterAlternates(layout())
        val enter = out.keys.first { it.type == KeyType.ENTER }
        assertEquals(listOf("?", "!", ","), enter.alternates)
        assertEquals(LayoutMutations.ENTER_ALTERNATES, enter.alternates)
    }

    @Test
    fun withEnterAlternatesLeavesOtherKeysUntouched() {
        val out = LayoutMutations.withEnterAlternates(layout())
        val q = out.keys.first { it.id == "q" }
        val comma = out.keys.first { it.id == "comma" }
        assertEquals(listOf("1"), q.alternates)
        assertTrue(comma.alternates.isEmpty())
        // Structure preserved (same key count, name, locale).
        assertEquals(3, out.keys.size)
        assertEquals("qwerty", out.name)
    }

    @Test
    fun withEnterAlternatesAcceptsACustomList() {
        val out = LayoutMutations.withEnterAlternates(layout(), listOf(";", ".", "?"))
        val enter = out.keys.first { it.type == KeyType.ENTER }
        assertEquals(listOf(";", ".", "?"), enter.alternates)
    }

    @Test
    fun withEnterAlternatesEmptyListLeavesEnterUnchanged() {
        val out = LayoutMutations.withEnterAlternates(layout(), emptyList())
        val enter = out.keys.first { it.type == KeyType.ENTER }
        assertTrue(enter.alternates.isEmpty())
    }

    @Test
    fun withEnterAlternatesIsANoopWhenThereIsNoEnterKey() {
        val noEnter = KeyboardLayout(
            "x", "x",
            listOf(Key("q", KeyType.CHAR, "q", "q", 0f, 0f, 0.1f, 0.25f)),
        )
        val out = LayoutMutations.withEnterAlternates(noEnter)
        assertTrue(out.keys.none { it.type == KeyType.ENTER })
        assertEquals(1, out.keys.size)
    }

    /** Home row mirroring the bundled layouts: "l" ends at 0.95, right pad free. */
    private fun homeRowLayout(): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.10f, 0.25f),
            Key("l", KeyType.CHAR, "l", "l", 0.85f, 0.25f, 0.10f, 0.25f),
        ),
    )

    @Test
    fun withApostropheKeyAppendsAChromelessKeyAndNudgesTheHomeRow() {
        val before = homeRowLayout()
        val out = LayoutMutations.withApostropheKey(before)
        val apos = out.keys.singleOrNull { it.output == "'" }
        assertTrue("apostrophe key not appended", apos != null)
        apos!!
        assertEquals(KeyType.CHAR, apos.type)
        assertEquals("'", apos.label)
        assertEquals(false, apos.isLetter)
        assertTrue("apostrophe should be chromeless", apos.chromeless)
        assertEquals(0.25f, apos.y)
        // Sits at the right edge, right of the nudged home row.
        assertTrue("x=${apos.x} w=${apos.w}", apos.x >= 0.95f && apos.x + apos.w <= 1.0f)
        // Home-row letters nudge left by the shift; relative order preserved.
        val lBefore = before.keys.first { it.id == "l" }.x
        val lAfter = out.keys.first { it.id == "l" }.x
        assertTrue("l should shift left: $lBefore -> $lAfter", lAfter < lBefore)
        assertEquals(LayoutMutations.APOSTROPHE_HOME_ROW_SHIFT, lBefore - lAfter, 1e-6f)
        assertEquals("l", out.keys.first { it.id == "l" }.output)
    }

    @Test
    fun withApostropheKeyIsIdempotent() {
        val once = LayoutMutations.withApostropheKey(homeRowLayout())
        val twice = LayoutMutations.withApostropheKey(once)
        assertEquals(1, twice.keys.count { it.output == "'" })
        assertEquals(once.keys.size, twice.keys.size)
        // The nudge is applied once, never doubled.
        assertEquals(
            once.keys.first { it.id == "l" }.x,
            twice.keys.first { it.id == "l" }.x,
            1e-6f,
        )
    }
}
