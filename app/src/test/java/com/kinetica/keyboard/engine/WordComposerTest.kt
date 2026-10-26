package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordComposerTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()
    private val predictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)

    private class Capture : WordComposer.Callbacks {
        var candidates: List<WordCandidate> = emptyList()
        var tentative: WordCandidate? = null
        var literal = ""
        var calls = 0

        override fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        ) {
            this.candidates = candidates
            this.tentative = tentative
            this.literal = literal
            calls++
        }
    }

    @Test
    fun decodeFiresPerTokenWithRefinement() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        assertEquals(1, cap.calls)
        assertEquals("t", cap.literal)
        composer.onToken(TestData.tap('h', g, 100))
        composer.onToken(TestData.tap('e', g, 200))
        assertEquals(3, cap.calls)
        assertEquals("the", cap.literal)
        assertEquals("the", cap.candidates.first().word)
    }

    @Test
    fun literalEmptyOnceSwipePresent() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('s', g, 0))
        composer.onToken(TestData.swipe("om", g, 200, 200))
        assertEquals("", cap.literal)
        assertTrue(composer.hasSwipeToken())
    }

    @Test
    fun aRecentWordSwappedOnTheBarIsSwappedInTheContextToo() {
        val composer = WordComposer(predictor, direct, direct, Capture())
        composer.commitWord("word")
        composer.commitWord("is")
        composer.replaceCommit(1, "word", "world")
        assertEquals(listOf("world", "is"), composer.contextSnapshot())
        // Only where the context still holds the word the bar showed.
        composer.replaceCommit(1, "word", "ward")
        composer.replaceCommit(4, "is", "was")
        assertEquals(listOf("world", "is"), composer.contextSnapshot())
    }

    @Test
    fun commitUpdatesContextWindow() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.commitWord("how")
        composer.commitWord("are")
        composer.commitWord("you")
        assertEquals(listOf("are", "you"), composer.contextSnapshot())
        assertFalse(composer.hasPendingWord)
    }

    @Test
    fun aReopenedCommitLeavesTheContext() {
        // `Fake`, an automatic space, then `C`: the word is in progress again, so it is not the
        // previous word of its own decode, nor of the pair the next commit learns.
        val composer = WordComposer(predictor, direct, direct, Capture())
        composer.commitWord("see")
        composer.commitWord("fake")
        assertTrue(composer.reopenLastCommit("Fake"))
        assertEquals(listOf("see"), composer.contextSnapshot())
        // Only the last commit: a word further back is not the one being reopened.
        assertFalse(composer.reopenLastCommit("fake"))
        assertFalse(composer.reopenLastCommit("other"))
        assertEquals(listOf("see"), composer.contextSnapshot())
    }

    @Test
    fun aReopenedCommitTheCursorLeavesComesBack() {
        // `hello`, `world`, the cursor parked after `world`, then moved away: the next word
        // follows `world` again, not `hello`.
        val composer = WordComposer(predictor, direct, direct, Capture())
        composer.commitWord("hello")
        composer.commitWord("world")
        assertTrue(composer.reopenLastCommit("world"))
        assertEquals(listOf("hello"), composer.contextSnapshot())
        composer.clear()
        composer.restoreLastCommit("World")
        assertEquals(listOf("hello", "world"), composer.contextSnapshot())
        // Never deeper than the context: the oldest goes.
        composer.restoreLastCommit("again")
        assertEquals(listOf("world", "again"), composer.contextSnapshot())
    }

    @Test
    fun takingTheLastGestureBackBringsBackTheDecodeBeforeIt() {
        // Two thumbs: a sloppy swipe, a tap from the other thumb, then the rest of the word.
        // Backspace takes the last one back, and the bar must read as it did before it.
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.sloppySwipe("some", g, 0, 300, 0.3f, StreamId.LEFT))
        composer.onToken(TestData.tap('t', g, 350, StreamId.RIGHT))
        val words = cap.candidates.map { it.word }
        val lead = cap.tentative?.word
        assertTrue("the two gestures decode to something", words.isNotEmpty())
        composer.onToken(TestData.sloppySwipe("hing", g, 450, 300, 0.3f, StreamId.LEFT))
        assertTrue("the third gesture changes the bar", cap.candidates.map { it.word } != words)
        assertEquals(3, composer.tokenCount)
        composer.truncate(2)
        assertEquals(2, composer.tokenCount)
        assertEquals(words, cap.candidates.map { it.word })
        assertEquals(lead, cap.tentative?.word)
        // A count past the buffer changes nothing.
        composer.truncate(5)
        assertEquals(2, composer.tokenCount)
    }

    @Test
    fun aWordTheAutomaticSpaceCommittedComesBackWithoutItsLastGesture() {
        // The same two-thumb buffer, committed by its space: put back without the last gesture,
        // it decodes as it did before that gesture, and the word is not its own previous word.
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.commitWord("see")
        composer.onToken(TestData.sloppySwipe("some", g, 0, 300, 0.3f, StreamId.LEFT))
        composer.onToken(TestData.tap('t', g, 350, StreamId.RIGHT))
        val words = cap.candidates.map { it.word }
        val lead = cap.tentative?.word
        composer.onToken(TestData.sloppySwipe("hing", g, 450, 300, 0.3f, StreamId.LEFT))
        val tokens = composer.tokensSnapshot()
        assertEquals(3, tokens.size)
        composer.commitWord("something")
        assertEquals(listOf("see", "something"), composer.contextSnapshot())
        assertTrue(composer.reopenLastCommit("something"))
        composer.seed(tokens.take(2))
        assertEquals(2, composer.tokenCount)
        assertEquals(words, cap.candidates.map { it.word })
        assertEquals(lead, cap.tentative?.word)
        assertEquals(listOf("see"), composer.contextSnapshot())
    }

    @Test
    fun takingTheFirstGestureBackLeavesNoWord() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        composer.truncate(0)
        assertFalse(composer.hasPendingWord)
        assertEquals("", composer.literal())
    }

    @Test
    fun clearAbandonsPendingWord() {
        val cap = Capture()
        val composer = WordComposer(predictor, direct, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        assertTrue(composer.hasPendingWord)
        composer.clear()
        assertFalse(composer.hasPendingWord)
        assertEquals("", composer.literal())
    }

    @Test
    fun aWordOnlyTheOtherLanguageHasCarriesItsOwnProvenance() {
        // A foreign word must not enter the active language's personal dictionary, which is
        // how "sonore" and "imposte" got in. Provenance rides on the candidate, so the commit
        // path learns the word into the dictionary it came from.
        // The active dictionary fits the swipe poorly ("tee", d=1.136 on the t-h-e path)
        // while the other language holds the exact word.
        val active = WordPredictor(
            Trie.build(listOf("tee" to 100)), BigramTable.EMPTY, g, language = "it",
        )
        val cap = Capture()
        val composer = WordComposer(active, direct, direct, cap)
        composer.alternatePredictor = WordPredictor(
            TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en",
        )
        composer.onToken(TestData.swipe("the", g, 0, 300))
        assertEquals("the", cap.tentative?.word)
        assertEquals("en", cap.tentative?.language)
        assertTrue("the active language stays pickable", cap.candidates.any { it.language == "it" })
    }

    @Test
    fun anActiveLanguageDecodeCarriesTheActiveLanguage() {
        val cap = Capture()
        val composer = WordComposer(
            WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g, language = "en"),
            direct, direct, cap,
        )
        composer.alternatePredictor = WordPredictor(
            Trie.build(listOf("mama" to 100)), BigramTable.EMPTY, g, language = "it",
        )
        composer.onToken(TestData.swipe("the", g, 0, 300))
        assertEquals("the", cap.tentative?.word)
        assertEquals("en", cap.tentative?.language)
    }

    @Test
    fun aFailedWorkerDoesNotPermanentlyStopFutureDecodes() {
        val queued = ArrayList<Runnable>()
        val manual = Executor { queued.add(it) }
        var failFirst = true
        var recoveredLiteral = ""
        val callbacks = object : WordComposer.Callbacks {
            override fun onCandidates(
                candidates: List<WordCandidate>,
                tentative: WordCandidate?,
                literal: String,
                generation: Int,
            ) {
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("synthetic callback failure")
                }
                recoveredLiteral = literal
            }
        }
        val composer = WordComposer(predictor, manual, direct, callbacks)
        composer.onToken(TestData.tap('t', g, 0))
        var failed = false
        try {
            queued.removeAt(0).run()
        } catch (_: IllegalStateException) {
            failed = true
        }
        // Contained in the worker: on a real executor an escaped exception kills the process.
        assertFalse("the synthetic failure must not escape the worker", failed)

        composer.onToken(TestData.tap('h', g, 100))
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals("th", recoveredLiteral)
    }

    @Test
    fun pendingDecodesAreCoalescedToTheLatestGeneration() {
        // Queue the worker without running it, then type several more letters. Only one
        // executor task should exist, and running it decodes the latest snapshot, not one
        // stale prefix after another.
        val queued = ArrayList<Runnable>()
        val manual = Executor { queued.add(it) }
        val cap = Capture()
        val composer = WordComposer(predictor, manual, direct, cap)
        composer.onToken(TestData.tap('t', g, 0))
        composer.onToken(TestData.tap('h', g, 100))
        composer.onToken(TestData.tap('e', g, 200))
        assertEquals(1, queued.size)

        queued.single().run()

        assertEquals(1, cap.calls)
        assertEquals("the", cap.literal)
        assertEquals("the", cap.candidates.first().word)
    }
}
