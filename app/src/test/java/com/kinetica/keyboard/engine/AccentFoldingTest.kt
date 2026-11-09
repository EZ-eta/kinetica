package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** Accented dictionaries: folded trie keys with per-node display variants. */
class AccentFoldingTest {

    private val g = TestData.qwertyGeometry()

    private fun dict(vararg rows: Pair<String, Int>): LoadedDictionary {
        val text = rows.joinToString("\n") { "${it.first}\t${it.second}" }
        return DictionaryLoader.load(BufferedReader(StringReader(text)))
    }

    @Test
    fun foldMapsAccentsAndSharpS() {
        assertEquals("perche", AccentFolder.fold("perché"))
        assertEquals("citta", AccentFolder.fold("città"))
        assertEquals("zazolc gesla jazn", AccentFolder.fold("zażółć gęślą jaźń"))
        assertEquals(
            "prilis zlutoucky kun dabelske ody mesto",
            AccentFolder.fold("příliš žluťoučký kůň ďábelské ódy město"),
        )
        assertEquals("strasse", AccentFolder.fold("straße"))
        // French: ÿ folds like the other y-accents, œ is the second digraph.
        assertEquals("soeur", AccentFolder.fold("sœur"))
        assertEquals("moyen", AccentFolder.fold("moÿen"))
        // Norwegian: æ and å onto "a", ø onto "o". They are letters of the alphabet, not
        // accents, and each folds onto the key it is drawn on.
        assertEquals("vare", AccentFolder.fold("være"))
        assertEquals("for", AccentFolder.fold("før"))
        assertEquals("mate", AccentFolder.fold("måte"))
        assertEquals("stotte", AccentFolder.fold("støtte"))
        // Romanian: the comma-below ș and ț fold like the other s/t-accents, and the
        // legacy cedilla spellings (still what some keyboards emit) fold with them.
        assertEquals("masina", AccentFolder.fold("mașină"))
        assertEquals("tara", AccentFolder.fold("țară"))
        assertEquals("si", AccentFolder.fold("şi"))
        assertEquals("caini", AccentFolder.fold("câini"))
        assertEquals("intrebare", AccentFolder.fold("întrebare"))
        val plain = "already plain"
        assertTrue(plain === AccentFolder.fold(plain))
    }

    @Test
    fun accentedWordDecodesFromBaseKeyTaps() {
        val d = dict("perché" to 9000, "il" to 20000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        val tokens = "perche".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = p.decode(tokens, emptyList())
        assertEquals("perché", result[0].word)
    }

    @Test
    fun collidingVariantsBothSurface() {
        val d = dict("e" to 100000, "è" to 90000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        val result = p.decode(listOf(TestData.tap('e', g, 0)), emptyList())
        val words = result.map { it.word }
        assertTrue("expected both variants in $words", words.containsAll(listOf("e", "è")))
        // Higher raw count must win the ranking.
        assertEquals("e", result[0].word)
    }

    @Test
    fun isWordDistinguishesVariantsFromFoldedKeys() {
        val d = dict("perché" to 9000, "e" to 100000, "è" to 90000, "casa" to 5000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        assertTrue(p.isWord("perché"))
        assertFalse("folded spelling is not a word", p.isWord("perche"))
        assertTrue(p.isWord("e"))
        assertTrue(p.isWord("è"))
        assertTrue(p.isWord("casa"))
    }

    @Test
    fun autocorrectRestoresAccent() {
        val d = dict("perché" to 9000, "casa" to 5000)
        val p = WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms)
        val tokens = "perche".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val candidates = p.decode(tokens, emptyList())
        val target = p.autocorrectTarget("perche", candidates, 0.85f)
        assertEquals("perché", target?.word)
    }

    private fun codes(text: String): List<Int>? = AccentFolder.accentedLetterCodes(text)?.toList()

    private fun keys(word: String): List<Int> = word.map { it - 'a' }

    @Test
    fun accentedLetterCodesAcceptOnlyRealAccents() {
        // What KineticaIME composes into the word instead of committing it: an accent, in
        // either case.
        assertEquals(keys("o"), codes("ó"))
        assertEquals(keys("a"), codes("à"))
        assertEquals(keys("n"), codes("ñ"))
        assertEquals(keys("a"), codes("Ą"))
        assertEquals(keys("e"), codes("ę"))
        assertEquals(keys("c"), codes("Ç"))
        assertEquals(keys("e"), codes("É"))
        assertEquals(keys("r"), codes("Ř"))
        assertEquals(keys("t"), codes("ť"))
        // The popup's own base cell is a letter but not an accented one: it keeps the
        // commit-then-insert path.
        assertNull(codes("o"))
        assertNull(codes("O"))
        // Everything else a popup, an edge swipe or the enter strip can insert.
        for (t in listOf("", "1", "?", ",", ";", "€", "ss", "óó", "😀")) {
            assertNull("'$t' must not compose", codes(t))
        }
    }

    @Test
    fun aTwoLetterFoldComposesAsTwoTaps() {
        // `ß` from the popup is a letter of `Grüße`: two taps on s, the word kept open.
        assertEquals(keys("ss"), codes("ß"))
        assertEquals(keys("ss"), codes("ẞ"))
        assertEquals(keys("oe"), codes("œ"))
        assertEquals(keys("oe"), codes("Œ"))
        assertEquals(keys("ij"), codes("ĳ"))
        assertEquals(keys("th"), codes("þ"))
    }

    @Test
    fun theItalianSchwaFoldsOntoE() {
        // Inclusive Italian: `carə` singular, `tuttɜ` plural, each where the e or a stood.
        assertEquals(keys("e"), codes("ə"))
        assertEquals(keys("e"), codes("ɜ"))
        assertEquals("care amice", AccentFolder.fold("carə amicə"))
        assertEquals("tutte", AccentFolder.fold("tuttɜ"))
        // A learned schwa word is reached by the e path and shown as written.
        val d = dict("care" to 900, "carə" to 40)
        val node = d.trie.nodeFor("care")
        assertTrue(node != -1)
        assertEquals(setOf("care", "carə"), d.forms[node]?.map { it.display }?.toSet())
    }

    @Test
    fun aLearnedSchwaFormIsNotHeldUntilALoadMergesIt() {
        // `carə` learned nine times in Italian and never offered by a swipe. The reload asked
        // whether the trie held it; folded onto `care`, which has no forms, the answer was yes.
        val bundled = dict("care" to 900)
        val p0 = WordPredictor(bundled.trie, BigramTable.EMPTY, g, bundled.forms)
        assertTrue("the old question", p0.isWordIgnoringCase("carə"))
        assertFalse(p0.holdsSpelling("carə"))
        assertTrue(p0.holdsSpelling("care"))
        assertTrue("case aside", p0.holdsSpelling("Care"))
        // After the load the form is held, and a swipe through c-a-r-e offers it.
        val merged = dict("care" to 900, "carə" to 40)
        val p1 = WordPredictor(merged.trie, BigramTable.EMPTY, g, merged.forms)
        assertTrue(p1.holdsSpelling("carə"))
        for (overshoot in listOf(0f, 0.25f, 0.45f)) {
            val words = p1.decode(listOf(TestData.sloppySwipe("care", g, 0L, 400L, overshootKw = overshoot)), emptyList())
                .map { it.word }
            assertTrue("overshoot $overshoot: $words", "carə" in words)
        }
    }

    @Test
    fun everyMarkedLatinLetterReachesItsKey() {

        // Decomposed letters no list ships yet: Latvian, Hungarian, Turkish, Esperanto, Welsh.
        assertEquals("meness", AccentFolder.fold("mēness"))
        assertEquals("kozott", AccentFolder.fold("között"))
        assertEquals("tores", AccentFolder.fold("tőreś"))
        assertEquals("dag", AccentFolder.fold("dağ"))
        assertEquals("cxu ghi", AccentFolder.fold("ĉxu ĝĥi"))
        assertEquals("dwr", AccentFolder.fold("dŵr"))
        assertEquals("viet", AccentFolder.fold("việt"))
        // No decomposition: a stroke, a missing dot, a middle dot.
        assertEquals("dakika", AccentFolder.fold("dakıka"))
        assertEquals("dobro", AccentFolder.fold("đobro"))
        assertEquals("col.legi", AccentFolder.fold("coŀ.legi"))
        for (c in "āēīōūőűğľĺŕėįųĉĝĥĵŝŭŵŷđðħıŀŧəɜ") {
            assertEquals("$c has no key", 1, codes(c.toString())?.size)
        }
    }

    @Test
    fun theLatinFoldLeavesOtherScriptsAlone() {
        // `й` decomposes to `и` but is a key of its own on both Cyrillic boards.
        assertEquals("йод", AccentFolder.fold("йод"))
        assertEquals("їжак", AccentFolder.fold("їжак"))
        assertEquals("еж", AccentFolder.fold("ёж"))
    }

    @Test
    fun everyPopupLetterOfALatinBoardComposes() {
        // A popup letter with no fold ended the word: `ē` on English `e` committed what came
        // before it. Read off the layout files: org.json is a stub on the JVM.
        val dir = listOf("src/main/assets/layouts", "app/src/main/assets/layouts")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory }
        assumeTrue("layouts not found", dir != null)
        val cell = Regex("\"([^\"\\\\]+)\"")
        val lists = Regex("\"output\": \"[a-z]\"[^}]*?\"alternates\": \\[([^\\]]*)\\]")
        var seen = 0
        for (f in dir!!.listFiles()!!.filter { it.name.endsWith(".json") && !it.name.startsWith("native_") }) {
            for (m in lists.findAll(f.readText())) {
                for (c in cell.findAll(m.groupValues[1]).map { it.groupValues[1] }) {
                    if (c.length != 1 || !c[0].isLetter() || c[0] in 'a'..'z' || c[0] in 'A'..'Z') continue
                    seen++
                    assertTrue("${f.name}: $c ends the word", codes(c) != null)
                }
            }
        }
        assertTrue("no popup letters read", seen > 100)
    }

    @Test
    fun englishDictionaryHasNoForms() {
        val d = dict("the" to 12000, "then" to 5000, "don't" to 2200)
        assertTrue(d.forms.isEmpty())
    }

    @Test
    fun foldAlsoFoldsCase() {
        // The trie alphabet has no capitals: Alphabet.codeOf admits only a-z
        // and an apostrophe, so a capitalized entry would encode to null and be
        // dropped silently. Case folding here lets a wordlist carry a capitalized
        // display form on a lowercase key.
        assertEquals("haus", AccentFolder.fold("Haus"))
        assertEquals("strasse", AccentFolder.fold("Straße"))
        assertEquals("uber", AccentFolder.fold("Über"))
        assertEquals("soeur", AccentFolder.fold("Sœur"))
        // Norwegian's own letters, capitalized as they are at a sentence start.
        assertEquals("ao", AccentFolder.fold("ÆØ"))
        assertEquals("vare", AccentFolder.fold("Være"))
    }

    @Test
    fun anAllLowercasePlainWordIsStillTheSameInstance() {
        // The identity optimization the loader relies on, kept across the case
        // change: every asset that was lowercase before still folds for free.
        val plain = "already plain"
        assertTrue(plain === AccentFolder.fold(plain))
    }
}
