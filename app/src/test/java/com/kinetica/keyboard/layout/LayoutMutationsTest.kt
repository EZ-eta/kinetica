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

    private companion object {
        val ALTERNATES = Regex("\"alternates\"\\s*:\\s*\\[([^]]*)]")
    }

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

    /** The bundled English alternates for the four keys that carry the most. */
    private fun accentLayout(nativeAccents: Boolean = false): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.10f, 0.25f,
                alternates = listOf("à", "á", "â", "ä", "ã", "å", "æ", "ā", "@")),
            Key("s", KeyType.CHAR, "s", "s", 0.15f, 0.25f, 0.10f, 0.25f,
                alternates = listOf("ß", "ś", "š", "#")),
            Key("q", KeyType.CHAR, "q", "q", 0f, 0f, 0.10f, 0.25f, alternates = listOf("1")),
            Key("comma", KeyType.CHAR, ",", ",", 0.15f, 0.75f, 0.1f, 0.25f),
        ),
        nativeAccents = nativeAccents,
    )

    @Test
    fun withoutForeignAlternatesKeepsOnlyDigitsAndSymbols() {
        val out = LayoutMutations.withoutForeignAlternates(accentLayout())
        assertEquals(listOf("@"), out.keys.first { it.id == "a" }.alternates)
        assertEquals(listOf("#"), out.keys.first { it.id == "s" }.alternates)
        // A key with no accent to lose is untouched, popup and hint intact.
        assertEquals(listOf("1"), out.keys.first { it.id == "q" }.alternates)
        assertTrue(out.keys.first { it.id == "comma" }.alternates.isEmpty())
        assertEquals(4, out.keys.size)
    }

    @Test
    fun withoutForeignAlternatesIsANoopForALayoutWhoseAccentsAreItsOwn() {
        // The whole point of declaring it: Italian, Spanish and Polish writers
        // keep "è", "ñ" and "ą" even with the setting on.
        val before = accentLayout(nativeAccents = true)
        val out = LayoutMutations.withoutForeignAlternates(before)
        assertEquals(before, out)
        assertEquals(9, out.keys.first { it.id == "a" }.alternates.size)
    }

    @Test
    fun withoutForeignAlternatesNeverEmptiesAPopup() {
        // A key whose alternates are ALL accents would lose its popup and its
        // corner hint, so it is left alone instead. No bundled key is like this
        // (see the asset guard below), which is why the rule can be this simple.
        val allAccents = KeyboardLayout(
            "x", "x",
            listOf(Key("e", KeyType.CHAR, "e", "e", 0f, 0f, 0.1f, 0.25f,
                alternates = listOf("è", "é"))),
        )
        val out = LayoutMutations.withoutForeignAlternates(allAccents)
        assertEquals(listOf("è", "é"), out.keys.first().alternates)
    }

    @Test
    fun withNumberPriorityFindsNothingLeftToReorderAfterTheTrim() {
        // The two settings compose rather than fight: order matters only in that
        // the trim must run first, which KineticaIME.alphaLayout does.
        val trimmed = LayoutMutations.withoutForeignAlternates(accentLayout())
        assertEquals(trimmed, LayoutMutations.withNumberPriority(trimmed))
    }

    @Test
    fun everyBundledAccentKeyKeepsANonLetterAlternate() {
        // The precondition withoutForeignAlternates rests on, guarded against a
        // future layout edit. Read as text on purpose: the JVM test runtime stubs
        // org.json, so LayoutLoader cannot be used here.
        for (name in listOf("qwerty", "qwerty_it", "qwerty_es", "qwerty_pl")) {
            val p = listOf(
                java.nio.file.Paths.get("src/main/assets/layouts/$name.json"),
                java.nio.file.Paths.get("app/src/main/assets/layouts/$name.json"),
            ).firstOrNull { java.nio.file.Files.exists(it) }
            org.junit.Assume.assumeTrue("layout asset $name not found", p != null)
            for (line in java.nio.file.Files.readAllLines(p!!)) {
                val arr = ALTERNATES.find(line)?.groupValues?.get(1) ?: continue
                val entries = arr.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                if (entries.none { it.first().isLetter() }) continue
                assertTrue(
                    "$name: ${line.trim()} would be left with an empty popup",
                    entries.any { !it.first().isLetter() },
                )
            }
        }
    }

    // ---- letter arrangements (QWERTZ / QZERTY) -----------------------------

    /** The three keys the two swaps touch, with the bundled English alternates. */
    private fun arrangementLayout(): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("w", KeyType.CHAR, "w", "w", 0.10f, 0.0f, 0.1f, 0.25f,
                alternates = listOf("2")),
            Key("y", KeyType.CHAR, "y", "y", 0.50f, 0.0f, 0.1f, 0.25f,
                alternates = listOf("\u00fd", "\u00ff", "6")),
            Key("z", KeyType.CHAR, "z", "z", 0.15f, 0.5f, 0.1f, 0.25f,
                alternates = listOf("\u017e", "\u017a", "\u017c", "'")),
            Key("a", KeyType.CHAR, "a", "a", 0.05f, 0.25f, 0.1f, 0.25f,
                alternates = listOf("\u00e0", "@")),
        ),
    )

    private fun keyAt(l: KeyboardLayout, x: Float, y: Float): Key =
        l.keys.first { it.x == x && it.y == y }

    @Test
    fun qwertyIsTheIdentityAndSoIsAnUnknownValue() {
        val before = arrangementLayout()
        assertEquals(before, LayoutMutations.withLetterArrangement(before, "qwerty"))
        // A stale or misspelt preference must not silently rearrange the board.
        assertEquals(before, LayoutMutations.withLetterArrangement(before, "dvorak"))
        assertEquals(before, LayoutMutations.withLetterArrangement(before, ""))
    }

    @Test
    fun qwertzPutsZInTheTopRowAndYInTheBottom() {
        val out = LayoutMutations.withLetterArrangement(arrangementLayout(), "qwertz")
        val top = keyAt(out, 0.50f, 0.0f)
        val bottom = keyAt(out, 0.15f, 0.5f)
        assertEquals("z", top.output)
        assertEquals("z", top.label)
        assertEquals("z", top.id)
        assertEquals("y", bottom.output)
        // Untouched keys stay exactly as they were.
        assertEquals("w", keyAt(out, 0.10f, 0.0f).output)
        assertEquals("a", keyAt(out, 0.05f, 0.25f).output)
        assertEquals(4, out.keys.size)
    }

    @Test
    fun qzertySwapsZAndWInstead() {
        val out = LayoutMutations.withLetterArrangement(arrangementLayout(), "qzerty")
        assertEquals("z", keyAt(out, 0.10f, 0.0f).output)
        assertEquals("w", keyAt(out, 0.15f, 0.5f).output)
        // Y is not involved in this one.
        assertEquals("y", keyAt(out, 0.50f, 0.0f).output)
    }

    @Test
    fun accentsFollowTheLetterAndDigitsStayWithThePosition() {
        // The rule the whole mutation rests on. Digits are positional on this
        // keyboard - the top row is 1-0 - while an accent belongs to its letter.
        val out = LayoutMutations.withLetterArrangement(arrangementLayout(), "qwertz")
        val top = keyAt(out, 0.50f, 0.0f)
        val bottom = keyAt(out, 0.15f, 0.5f)
        assertEquals(listOf("\u017e", "\u017a", "\u017c", "6"), top.alternates)
        assertEquals(listOf("\u00fd", "\u00ff", "'"), bottom.alternates)
    }

    @Test
    fun theImplicitDigitSwipesSurviveTheSwap() {
        // withImplicitAlternates takes the first NON-letter alternate per key,
        // so this is the assertion that the top row still offers 6 rather than
        // the apostrophe that would arrive with a whole-key swap.
        val out = LayoutMutations.withLetterArrangement(arrangementLayout(), "qwertz")
        val top = keyAt(out, 0.50f, 0.0f)
        assertEquals("6", top.alternates.first { it.firstOrNull()?.isLetter() != true })
        val bottom = keyAt(out, 0.15f, 0.5f)
        assertEquals("'", bottom.alternates.first { it.firstOrNull()?.isLetter() != true })
    }

    @Test
    fun theCornerHintStaysAnAccentJustAsItIsAuthored() {
        // hintChar is the first alternate, so accents-first ordering has to
        // survive the rebuild or every hint on the board changes.
        val out = LayoutMutations.withLetterArrangement(arrangementLayout(), "qwertz")
        assertEquals("\u017e", keyAt(out, 0.50f, 0.0f).hintChar)
        assertEquals("\u00fd", keyAt(out, 0.15f, 0.5f).hintChar)
    }

    @Test
    fun aLetterWithNoAccentsSwapsCleanly() {
        // The it/es case: their w and z carry only a symbol, so the letter half
        // of the swap is empty and must not drop the position's digit.
        val plain = KeyboardLayout(
            "qwerty_it", "it_IT",
            listOf(
                Key("w", KeyType.CHAR, "w", "w", 0.10f, 0.0f, 0.1f, 0.25f,
                    alternates = listOf("2")),
                Key("z", KeyType.CHAR, "z", "z", 0.15f, 0.5f, 0.1f, 0.25f,
                    alternates = listOf("'")),
            ),
        )
        val out = LayoutMutations.withLetterArrangement(plain, "qzerty")
        assertEquals("z", keyAt(out, 0.10f, 0.0f).output)
        assertEquals(listOf("2"), keyAt(out, 0.10f, 0.0f).alternates)
        assertEquals("w", keyAt(out, 0.15f, 0.5f).output)
        assertEquals(listOf("'"), keyAt(out, 0.15f, 0.5f).alternates)
    }

    @Test
    fun aLayoutMissingOneOfThePairIsLeftAlone() {
        val noZ = KeyboardLayout(
            "x", "x",
            listOf(Key("y", KeyType.CHAR, "y", "y", 0.5f, 0f, 0.1f, 0.25f)),
        )
        assertEquals(noZ, LayoutMutations.withLetterArrangement(noZ, "qwertz"))
    }

    @Test
    fun polishLayoutExposesEveryNativeLetter() {
        val p = listOf(
            java.nio.file.Paths.get("src/main/assets/layouts/qwerty_pl.json"),
            java.nio.file.Paths.get("app/src/main/assets/layouts/qwerty_pl.json"),
        ).firstOrNull { java.nio.file.Files.exists(it) }
        org.junit.Assume.assumeTrue("Polish layout asset not found", p != null)
        val lines = java.nio.file.Files.readAllLines(p!!)
        val expected = mapOf(
            "a" to listOf("ą"),
            "c" to listOf("ć"),
            "e" to listOf("ę"),
            "l" to listOf("ł"),
            "n" to listOf("ń"),
            "o" to listOf("ó"),
            "s" to listOf("ś"),
            "z" to listOf("ź", "ż"),
        )
        for ((key, letters) in expected) {
            val line = lines.firstOrNull { it.contains("\"id\": \"$key\"") }
            assertTrue("qwerty_pl is missing key $key", line != null)
            for (letter in letters) {
                assertTrue(
                    "qwerty_pl key $key is missing $letter: $line",
                    line!!.contains("\"$letter\""),
                )
            }
        }
        assertTrue(lines.any { it.contains("\"nativeAccents\": true") })
    }

    // ---- user-editable punctuation flyouts ---------------------------------

    /** Period and comma with the alternates all four bundled layouts author. */
    private fun punctuationLayout(): KeyboardLayout = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("comma", KeyType.CHAR, ",", ",", 0.15f, 0.75f, 0.1f, 0.25f,
                alternates = listOf("_", "[", "]", "\u2013", "\u2014")),
            Key("period", KeyType.CHAR, ".", ".", 0.75f, 0.75f, 0.1f, 0.25f,
                alternates = listOf("\u2026", "\u00ab", "\u00bb")),
            Key("q", KeyType.CHAR, "q", "q", 0f, 0f, 0.1f, 0.25f, alternates = listOf("1")),
        ),
    )

    @Test
    fun aBlankListLeavesTheLayoutsOwnPunctuation() {
        // The whole reason the preference defaults to blank: the layout JSON stays
        // the source of truth, so a future language layout with different
        // punctuation is not overridden by a global default.
        val before = punctuationLayout()
        assertEquals(before, LayoutMutations.withPunctuationAlternates(before, emptyList(), emptyList()))
    }

    @Test
    fun eachKeyIsReplacedIndependently() {
        val out = LayoutMutations.withPunctuationAlternates(
            punctuationLayout(), listOf("!", "?"), emptyList(),
        )
        assertEquals(listOf("!", "?"), out.keys.first { it.id == "period" }.alternates)
        // Comma untouched, because its list was empty.
        assertEquals(5, out.keys.first { it.id == "comma" }.alternates.size)
        // And no other key is disturbed.
        assertEquals(listOf("1"), out.keys.first { it.id == "q" }.alternates)
    }

    @Test
    fun bothKeysTakeTheirOwnList() {
        val out = LayoutMutations.withPunctuationAlternates(
            punctuationLayout(), listOf(";"), listOf(":", "-"),
        )
        assertEquals(listOf(";"), out.keys.first { it.id == "period" }.alternates)
        assertEquals(listOf(":", "-"), out.keys.first { it.id == "comma" }.alternates)
    }

    @Test
    fun theEmojiEntryStillLandsFirstOnAUserCommaList() {
        // Ordering in KineticaIME.alphaLayout is load-bearing: the punctuation
        // mutation runs BEFORE withEmojiOnComma, so the emoji cell still leads.
        val custom = LayoutMutations.withPunctuationAlternates(
            punctuationLayout(), emptyList(), listOf(":", "-"),
        )
        val out = LayoutMutations.withEmojiOnComma(custom)
        assertEquals(
            listOf(LayoutMutations.EMOJI_ALTERNATE, ":", "-"),
            out.keys.first { it.id == "comma" }.alternates,
        )
    }

    @Test
    fun aRepurposedCommaKeepsTheUserListBehindTheComma() {
        // withCommaKey runs last and pushes "," to the front so the character
        // stays reachable; the user's own symbols must survive after it.
        val custom = LayoutMutations.withPunctuationAlternates(
            punctuationLayout(), emptyList(), listOf(":", "-"),
        )
        val out = LayoutMutations.withCommaKey(custom, "paste", "")
        val comma = out.keys.first { it.id == "comma" }
        assertEquals(LayoutMutations.ACTION_PASTE, comma.output)
        assertEquals(listOf(",", ":", "-"), comma.alternates)
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
