package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.WordPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which word the idle bar predicts from, and how two languages' predictions share it. */
class NextWordBarTest {

    @Test
    fun aFinishedWordAndItsSpaceArePredictedFrom() {
        assertEquals("think", previousWordForPrediction("I think "))
        assertEquals("think", previousWordForPrediction("I think  "))
        assertEquals("don't", previousWordForPrediction("I don't "))
        assertEquals("perché", previousWordForPrediction("non so perché "))
        assertEquals("hello", previousWordForPrediction("hello "))
        assertEquals("quote", previousWordForPrediction("a \"quote "))
    }

    @Test
    fun nothingIsPredictedMidWordOrAfterPunctuation() {
        assertNull("mid-word, the user is still writing it", previousWordForPrediction("I think"))
        assertNull("a sentence end has no pair across it", previousWordForPrediction("Done. "))
        assertNull(previousWordForPrediction("ok, "))
        assertNull(previousWordForPrediction("really? "))
        assertNull("the last piece of an address is not a word", previousWordForPrediction("name@mail.com "))
        assertNull(previousWordForPrediction("a-b "))
        assertNull(previousWordForPrediction(" "))
        assertNull(previousWordForPrediction(""))
        assertNull(previousWordForPrediction(null))
        assertNull("a new line is a new start", previousWordForPrediction("line\n"))
    }

    private fun nw(word: String, score: Float, lang: String) = WordPredictor.NextWord(word, score, lang)

    @Test
    fun twoLanguagesShareTheBarStrongestFirstWithOneEntryPerWord() {
        val it = listOf(nw("che", 1.9f, "it"), nw("non", 1.5f, "it"))
        val en = listOf(nw("the", 1.8f, "en"), nw("non", 1.7f, "en"))
        assertEquals(listOf("che", "the", "non"), mergeNextWords(it, en, 10).map { it.word })
        assertEquals("the stronger reading of a shared word wins", "en", mergeNextWords(it, en, 10)[2].language)
        assertEquals(listOf("che", "the"), mergeNextWords(it, en, 2).map { it.word })
    }

    @Test
    fun theCorrectionsBesideTheNextWordsLeadWithTheTypedLettersThenTheRunnersUp() {
        val m = CommitMemory()
        // A swipe: no letters typed, so the runners-up in rank order.
        m.onCommit("you", stripShown = true, alternatives = listOf("your", "yoi", "yo"))
        assertEquals(listOf("your"), m.besideWords(1))
        assertEquals(listOf("your", "yoi"), m.besideWords(2))
        assertEquals(listOf("your", "yoi", "yo"), m.besideWords(3))
        // An autocorrect: the letters typed are the way back, ahead of the runners-up.
        m.onCommit("hello", stripShown = true, alternatives = listOf("help", "hrllo"), typed = "hrllo")
        assertEquals(listOf("hrllo", "help"), m.besideWords(3))
        // A case-only alternative is the same word and is skipped.
        m.onCommit("mark", stripShown = true, alternatives = listOf("Mark", "march"))
        assertEquals(listOf("march"), m.besideWords(2))
        m.onCommit("solo", stripShown = true, alternatives = listOf("Solo"))
        assertEquals(emptyList<String>(), m.besideWords(3))
    }

    @Test
    fun aSwapPutsTheReplacedWordFirstSoASecondTapUndoesIt() {
        val m = CommitMemory()
        m.onCommit("you", stripShown = true, alternatives = listOf("your", "yoi"))
        m.onReplaced("your")
        assertEquals(listOf("you", "yoi"), m.besideWords(2))
        m.onReplaced("you")
        assertEquals(listOf("your", "yoi"), m.besideWords(2))
        // A re-case is no swap of word: the zones keep their alternatives.
        m.onReplaced("YOU")
        assertEquals(listOf("your", "yoi"), m.besideWords(2))
        // The next commit starts afresh.
        m.onCommit("then", stripShown = true, alternatives = listOf("than"))
        assertEquals(listOf("than"), m.besideWords(2))
    }

    @Test
    fun theNextWordsShowBesideTheCorrectionsOnlyWithRecentWordsOff() {
        assertTrue(showsBeside(true, nextWordOn = true, besideOn = true, recentWords = false, corrections = 2, nextFound = true))
        // Recent words on: their columns hold the alternatives and no strip was up, as before.
        assertFalse(showsBeside(true, nextWordOn = true, besideOn = true, recentWords = true, corrections = 2, nextFound = true))
        // The switches.
        assertFalse(showsBeside(true, nextWordOn = true, besideOn = false, recentWords = false, corrections = 2, nextFound = true))
        assertFalse(showsBeside(true, nextWordOn = false, besideOn = true, recentWords = false, corrections = 2, nextFound = true))
    }

    @Test
    fun withNoNextWordTheStripStays() {
        // After a period, or a word nothing follows: the whole strip, so no correction is lost.
        assertFalse(showsBeside(true, nextWordOn = true, besideOn = true, recentWords = false, corrections = 2, nextFound = false))
        assertFalse(showsBeside(true, nextWordOn = true, besideOn = true, recentWords = false, corrections = 0, nextFound = true))
        // No strip, nothing to put beside.
        assertFalse(showsBeside(false, nextWordOn = true, besideOn = true, recentWords = false, corrections = 2, nextFound = true))
    }

    @Test
    fun twoCorrectionsStandBesideTheNextWordsByDefault() {
        assertEquals(2, com.kinetica.keyboard.settings.KeyboardConfig.parseBesideCount(null))
        assertEquals(1, com.kinetica.keyboard.settings.KeyboardConfig.parseBesideCount("1"))
        assertEquals(3, com.kinetica.keyboard.settings.KeyboardConfig.parseBesideCount(" 3 "))
        // Out of range or unreadable: the default, never an empty or crowded bar.
        assertEquals(2, com.kinetica.keyboard.settings.KeyboardConfig.parseBesideCount("0"))
        assertEquals(2, com.kinetica.keyboard.settings.KeyboardConfig.parseBesideCount("9"))
        assertEquals(2, com.kinetica.keyboard.settings.KeyboardConfig.parseBesideCount("two"))
        val xml = com.kinetica.keyboard.settings.PrefsXml.read()
        val row = Regex("""android:key="pref_next_word_beside_count"[^>]*>""").find(xml)?.value
        requireNotNull(row) { "pref_next_word_beside_count not in keyboard_prefs.xml" }
        assertTrue(row, row.contains("android:defaultValue=\"${com.kinetica.keyboard.settings.Prefs.DEFAULT_NEXT_WORD_BESIDE_COUNT}\""))
        assertTrue(row, row.contains("android:dependency=\"pref_next_word_beside\""))
    }

    @Test
    fun theBesideBarIsOnByDefault() {
        assertTrue(com.kinetica.keyboard.settings.Prefs.DEFAULT_NEXT_WORD_BESIDE)
        val xml = com.kinetica.keyboard.settings.PrefsXml.read()
        val row = Regex("""android:key="pref_next_word_beside"[^>]*>""").find(xml)?.value
        requireNotNull(row) { "pref_next_word_beside not in keyboard_prefs.xml" }
        assertTrue(row, row.contains("android:defaultValue=\"true\""))
        assertTrue(row, row.contains("android:dependency=\"pref_next_word\""))
    }
}
