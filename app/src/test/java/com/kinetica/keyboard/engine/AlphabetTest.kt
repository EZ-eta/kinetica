package com.kinetica.keyboard.engine

import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One alphabet per script, and a trie wide enough for the widest. */
class AlphabetTest {

    @Test
    fun latinKeepsTheCodesEveryEarlierBuildUsed() {
        val a = Alphabet.LATIN
        for (c in 'a'..'z') assertEquals(c - 'a', a.codeOf(c))
        assertEquals(26, a.codeOf('\''))
        assertEquals(Alphabet.SIZE, a.size)
        assertEquals(Alphabet.LETTERS, a.letterCount)
        assertEquals(Alphabet.APOSTROPHE, a.apostrophe)
        assertEquals(-1, a.codeOf('é'))
        assertEquals(-1, a.codeOf('A'))
        assertEquals(-1, a.codeOf('ж'))
    }

    @Test
    fun eachScriptCodesOnlyItsOwnLetters() {
        assertEquals(32, Alphabet.CYRILLIC.letterCount)
        assertEquals(-1, Alphabet.CYRILLIC.apostrophe)
        assertEquals(27, Alphabet.HEBREW.letterCount)
        assertEquals(27, Alphabet.HEBREW.apostrophe)
        assertEquals(33, Alphabet.ARABIC.letterCount)
        assertEquals(-1, Alphabet.CYRILLIC.codeOf('a'))
        assertEquals(-1, Alphabet.LATIN.codeOf('א'))
        assertEquals(-1, Alphabet.CYRILLIC.codeOf('\''))
        for (a in listOf(Alphabet.LATIN, Alphabet.CYRILLIC, Alphabet.HEBREW, Alphabet.ARABIC)) {
            for (code in 0 until a.size) assertEquals(code, a.codeOf(a.charOf(code)))
        }
    }

    @Test
    fun aScriptAndALanguageNameTheirAlphabet() {
        assertEquals(Alphabet.CYRILLIC, Alphabet.forScript("cyrillic"))
        assertEquals(Alphabet.LATIN, Alphabet.forScript(null))
        assertEquals(Alphabet.LATIN, Alphabet.forScript("klingon"))
        assertEquals(Alphabet.ARABIC, Alphabet.forLanguage("ar"))
        assertEquals(Alphabet.LATIN, Alphabet.forLanguage("it"))
    }

    @Test
    fun aRootWithThirtyTwoChildrenKeepsThemAll() {
        // Cyrillic's 32 letters each start a word: a five-bit child count wrapped to zero here.
        val a = Alphabet.CYRILLIC
        val words = a.letters.map { "${it}а" to 10 } + listOf("мама" to 50, "дом" to 40)
        val trie = Trie.build(words, a)
        assertEquals(32, trie.childCount(trie.root))
        for (c in a.letters) assertTrue("$c", trie.contains("${c}а"))
        assertTrue(trie.contains("мама"))
        assertEquals("мама", trie.wordOf(trie.nodeFor("мама")))
        assertFalse(trie.contains("mama"))
    }

    @Test
    fun arabicsThirtyThreeLettersFitTheTrie() {
        val a = Alphabet.ARABIC
        val trie = Trie.build(a.letters.map { "$it" + "ا" to 5 }, a)
        assertEquals(33, trie.childCount(trie.root))
        assertEquals(33, trie.wordCount)
    }

    @Test
    fun aKeyIsALetterOnlyInItsOwnBoardsAlphabet() {
        fun key(out: String, a: Alphabet) = Key("k", KeyType.CHAR, out, out, 0f, 0f, 0.1f, 0.25f, alphabet = a)
        assertTrue(key("ж", Alphabet.CYRILLIC).isLetter)
        assertEquals(Alphabet.CYRILLIC.codeOf('ж'), key("ж", Alphabet.CYRILLIC).letterCode)
        // A symbol page is Latin: `π` there is a symbol, as it always was.
        assertFalse(key("π", Alphabet.LATIN).isLetter)
        assertFalse(key("ж", Alphabet.LATIN).isLetter)
        assertEquals(-1, key("?", Alphabet.CYRILLIC).letterCode)
        assertEquals(7, key("h", Alphabet.LATIN).letterCode)
    }

    @Test
    fun anyKeyThatTypesOneCharacterIsAChordKey() {
        // Chords are keyed by the character, so another script and the symbol pages have them
        // too. A key that types no character of its own has none.
        fun key(out: String, a: Alphabet, type: KeyType = KeyType.CHAR) = Key("k", type, out, out, 0f, 0f, 0.1f, 0.25f, alphabet = a)
        assertEquals('h', key("h", Alphabet.LATIN).chordChar)
        assertEquals('й', key("й", Alphabet.CYRILLIC).chordChar)
        assertEquals('ש', key("ש", Alphabet.HEBREW).chordChar)
        assertEquals('1', key("1", Alphabet.LATIN).chordChar)
        assertEquals(',', key(",", Alphabet.LATIN).chordChar)
        assertEquals('ж', key("Ж", Alphabet.CYRILLIC).chordChar)
        assertEquals(null, key(":-)", Alphabet.LATIN).chordChar)
        assertEquals(null, key("", Alphabet.LATIN, KeyType.ENTER).chordChar)
        // A Cyrillic key's letter code stays inside its own alphabet, the range the burst indexes.
        assertTrue(key("я", Alphabet.CYRILLIC).letterCode in 0 until Alphabet.CYRILLIC.letterCount)
    }

    @Test
    fun theFoldReachesEachScriptsKeys() {
        assertEquals("ещё", "ещё")
        assertEquals("еще", AccentFolder.fold("ещё"))
        assertEquals("еще", AccentFolder.fold("ЕЩЁ"))
        assertEquals("امس", AccentFolder.fold("أمس"))
        assertEquals("اسلام", AccentFolder.fold("إسلام"))
        // Short vowels and tatweel are dropped: text is written without them.
        assertEquals("كتب", AccentFolder.fold("كَتَبَ"))
        assertEquals("كتب", AccentFolder.fold("كـتـب"))
        // Hebrew folds nothing: finals are keys of their own.
        assertEquals("שלום", AccentFolder.fold("שלום"))
        assertArrayEquals(Alphabet.HEBREW.encode("שלום"), Alphabet.HEBREW.encode(AccentFolder.fold("שלום")))
        assertEquals(Alphabet.CYRILLIC.codeOf('е'), AccentFolder.accentedLetterCode("ё", Alphabet.CYRILLIC))
        assertNull(Alphabet.LATIN.encode("дом"))
    }
}
