package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which characters take back an automatically inserted space.
 *
 * Swipe "Hi", let autospace add its space, tap "!" and the result read "Hi !".
 * Only the automatic space is taken back - a space the user typed is theirs - and
 * only for punctuation that genuinely sits against the word.
 */
class HugsPreviousWordTest {

    @Test
    fun sentenceAndClausePunctuationHugs() {
        for (c in listOf(".", ",", "!", "?", ";", ":")) {
            assertTrue("$c should hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun closingBracketsAndQuotesHug() {
        for (c in listOf(")", "]", "}", "»", "…")) {
            assertTrue("$c should hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun anOpeningBracketDoesNot() {
        // "a (b)" wants the space that is already there.
        for (c in listOf("(", "[", "{", "«")) {
            assertFalse("$c should not hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun aDashKeepsItsSpace() {
        // "one - two" is the common case, and an em dash is punctuation that
        // takes a space either side - which is why this is an explicit list and
        // not a character-class test.
        for (c in listOf("-", "–", "—", "_")) {
            assertFalse("$c should not hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun lettersDigitsAndSymbolsAreNotPunctuation() {
        for (c in listOf("a", "Z", "7", "@", "#", "$", "%", "+", "=", "/", "*")) {
            assertFalse("$c should not hug", hugsPreviousWord(c))
        }
    }

    @Test
    fun anApostropheDoesNotHugHere() {
        // It hugs in "don't", but that arrives through the letter path or the
        // apostrophe key, never as punctuation after an autospace. Treating it as
        // hugging would eat the space in "he said 'hi'".
        assertFalse(hugsPreviousWord("'"))
        assertFalse(hugsPreviousWord("\""))
    }

    @Test
    fun onlySingleCharactersQualify() {
        // Chord expansions and the custom comma text arrive through the same path.
        assertFalse(hugsPreviousWord(""))
        assertFalse(hugsPreviousWord("..."))
        assertFalse(hugsPreviousWord("!!"))
        assertFalse(hugsPreviousWord(" "))
        assertFalse(hugsPreviousWord("\n"))
        assertFalse(hugsPreviousWord("kind regards,"))
    }
}
