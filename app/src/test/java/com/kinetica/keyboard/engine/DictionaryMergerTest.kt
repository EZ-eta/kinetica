package com.kinetica.keyboard.engine

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryMergerTest {

    private fun reader(s: String): BufferedReader = BufferedReader(StringReader(s))

    private val primary = listOf("the" to 1_000_000, "of" to 500_000, "cat" to 100)

    @Test
    fun mergesNewWordsOntoPrimaryScale() {
        val aosp = """
            dictionary=main:en_us,locale=en_US,date=1414726260,version=54
             word=the,f=222
             word=hello,f=255
             word=zyxwvut,f=0
        """.trimIndent()
        val result = DictionaryMerger.merge(primary, reader(aosp), "en")
        // "the" is already primary: not double-added.
        assertEquals(1_000_000, result.rows.first { it.first == "the" }.second)
        // f=255 de-quantizes to the primary maximum.
        assertEquals(1_000_000, result.rows.first { it.first == "hello" }.second)
        // f=0 de-quantizes to 1.
        assertEquals(1, result.rows.first { it.first == "zyxwvut" }.second)
        assertEquals(2, result.added)
        assertEquals(3, result.aospParsed)
        // Sorted by count descending.
        for (i in 1 until result.rows.size) {
            assertTrue(result.rows[i - 1].second >= result.rows[i].second)
        }
    }

    @Test
    fun dropsFlaggedAndMalformedEntries() {
        val aosp = """
             word=asap,f=100,flags=abbreviation
             word=badword,f=100,possibly_offensive=true
             word=verylongwordthatgoesonandon,f=100
             word=émigré,f=100
             word=fine,f=100
             not_a_word_line=1
        """.trimIndent()
        val result = DictionaryMerger.merge(primary, reader(aosp), "en")
        val words = result.rows.map { it.first }
        assertFalse(words.contains("asap"))
        assertFalse(words.contains("badword"))
        assertFalse(words.contains("verylongwordthatgoesonandon"))
        // Accented forms are rejected for en but pass the it pattern.
        assertFalse(words.contains("émigré"))
        assertTrue(words.contains("fine"))
        assertEquals(1, result.added)
    }

    @Test
    fun italianPatternAdmitsAccentedVowels() {
        val aosp = """
             word=perché,f=200
             word=ciò,f=180
        """.trimIndent()
        val result = DictionaryMerger.merge(primary, reader(aosp), "it")
        val words = result.rows.map { it.first }
        assertTrue(words.contains("perché"))
        assertTrue(words.contains("ciò"))
    }

    @Test
    fun spanishPatternAdmitsAccentedWords() {
        val aosp = """
             word=señal,f=200
             word=también,f=180
             word=città,f=150
        """.trimIndent()
        val result = DictionaryMerger.merge(primary, reader(aosp), "es")
        val words = result.rows.map { it.first }
        assertTrue(words.contains("señal"))
        assertTrue(words.contains("también"))
        // Grave accents are not Spanish orthography: filtered on import.
        assertFalse(words.contains("città"))
    }

    @Test
    fun readPrimaryParsesTabSeparatedRows() {
        val rows = DictionaryMerger.readPrimary(reader("the\t1000\nbroken line\nof\t500\n"))
        assertEquals(listOf("the" to 1000, "of" to 500), rows)
    }
}
