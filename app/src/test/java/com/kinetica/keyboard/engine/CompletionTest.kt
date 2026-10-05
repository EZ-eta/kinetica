package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.ime.literalZone
import com.kinetica.keyboard.ime.notBlocked
import com.kinetica.keyboard.ime.suggestionZoneWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live tap-typing completions: an all-tap prefix surfaces its
 * dictionary extensions as pick-only Source.COMPLETION candidates. The two
 * hard rules locked here: exact-length matches always outrank their own
 * extensions, and a COMPLETION best candidate never autocorrects: space
 * after "th" must commit "th", never "the".
 */
class CompletionTest {

    private val g = TestData.qwertyGeometry()
    private val predictor = WordPredictor(TestData.smallDictionary(), BigramTable.EMPTY, g)

    private fun taps(word: String) =
        word.mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }

    private fun words(candidates: List<WordCandidate>): List<String> =
        candidates.map { it.word }

    @Test
    fun twoTapPrefixSurfacesTopCompletion() {
        // t,h is not a word; its most frequent extension must be immediately
        // pickable near the top of the strip.
        val result = predictor.decode(taps("th"), emptyList())
        assertTrue(
            "'the' not in top-3 of ${words(result)}",
            words(result).take(3).contains("the"),
        )
        val the = result.first { it.word == "the" }
        assertEquals(WordCandidate.Source.COMPLETION, the.source)
    }

    @Test
    fun longPrefixSurfacesLongCompletion() {
        val result = predictor.decode(taps("somet"), emptyList())
        assertTrue(
            "'something' missing from ${words(result)}",
            words(result).contains("something"),
        )
    }

    @Test
    fun exactWordOutranksItsOwnExtensions() {
        // t,h,e: "the" (exact, dTotal 0) must stay top-1 over then/them/they.
        val result = predictor.decode(taps("the"), emptyList())
        assertEquals("the", result[0].word)
        assertEquals(WordCandidate.Source.EXACT_TAP, result[0].source)
        assertTrue("expected extensions too, got ${words(result)}", result.size >= 3)
    }

    @Test
    fun completionsReachApostropheWords() {
        // d,o,n: the descent walks through the trie apostrophe node, so the
        // contraction is pickable without the apostrophe key.
        val result = predictor.decode(taps("don"), emptyList())
        assertTrue("'don't' missing from ${words(result)}", words(result).contains("don't"))
    }

    @Test
    fun completionBestNeverAutocorrects() {
        // Hard product rule: completions are pick-only. The threshold here is
        // permissive (0.5 < confidence 1/1.25 = 0.8) so this test fails against
        // any implementation missing the COMPLETION guard.
        val result = predictor.decode(taps("th"), emptyList())
        assertEquals(WordCandidate.Source.COMPLETION, result[0].source)
        assertNull(predictor.autocorrectTarget("th", result, 0.5f))
    }

    @Test
    fun singleTapDoesNotComplete() {
        // COMPLETION_MIN_PREFIX = 2: one tap must not flood the strip with
        // half the dictionary.
        val result = predictor.decode(taps("t"), emptyList())
        assertTrue(
            "unexpected completions: ${words(result)}",
            result.none { it.source == WordCandidate.Source.COMPLETION },
        )
    }

    @Test
    fun swipeBearingBuffersEmitNoCompletions() {
        // Completions are an all-tap exact-pass feature; segment-bearing
        // patterns (pure swipe and merged dual-stream) must be untouched, so
        // completions cannot bleed into the resume-family goldens.
        val swipeOnly = predictor.decode(
            listOf(TestData.swipe("the", g, 0, 300)), emptyList(),
        )
        assertTrue(swipeOnly.none { it.source == WordCandidate.Source.COMPLETION })

        val merged = predictor.decode(
            listOf(
                TestData.tap('s', g, 0, StreamId.LEFT),
                TestData.swipe("om", g, 60, 220, StreamId.RIGHT),
                TestData.tap('e', g, 310, StreamId.LEFT),
                TestData.swipe("thing", g, 360, 440, StreamId.RIGHT),
            ),
            emptyList(),
        )
        assertEquals("something", merged[0].word)
        assertTrue(merged.none { it.source == WordCandidate.Source.COMPLETION })
    }

    @Test
    fun junkPrefixCompletesToNothing() {
        // x,q,z reaches no trie path: no completions and no candidates in the
        // small dictionary, so the literal zone is the user's only way to
        // commit it (see suggestionZoneWords).
        val result = predictor.decode(taps("xqz"), emptyList())
        assertTrue(
            "unexpected completions: ${words(result)}",
            result.none { it.source == WordCandidate.Source.COMPLETION },
        )
    }

    @Test
    fun literalIsTheLastZoneAndNeverDuplicated() {
        // Part B contract (KineticaIME.pushSuggestions): the all-tap literal
        // is appended as the final pickable zone; a literal already among the
        // candidates, or the empty literal of a swipe-bearing buffer, adds
        // nothing.
        assertEquals(listOf("xqz"), suggestionZoneWords(emptyList(), "xqz"))
        assertEquals(
            listOf("the", "then", "xq"),
            suggestionZoneWords(listOf("the", "then"), "xq"),
        )
        assertEquals(
            listOf("the", "then"),
            suggestionZoneWords(listOf("the", "then"), "the"),
        )
        assertEquals(listOf("the"), suggestionZoneWords(listOf("the"), ""))
    }

    @Test
    fun lettersWithoutAccentsAreNotOfferedWhileAutocorrectIsOn() {
        // A spelling with the accents left off: `pojsc` is not offered beside `pójść`, unless
        // autocorrect is off.
        assertEquals("", literalZone("pojsc", autocorrects = true, leavesAccentsOff = true))
        assertEquals("pojsc", literalZone("pojsc", autocorrects = false, leavesAccentsOff = true))
        assertEquals("xqz", literalZone("xqz", autocorrects = true, leavesAccentsOff = false))
    }

    @Test
    fun aBlockedWordIsNeverOfferedBack() {
        // `anb` blocked for `and` must not come back as the typed letters, the strip's way back
        // or a recent column, none of which the trie decides.
        assertEquals("", literalZone("anb", autocorrects = false, leavesAccentsOff = false, blocked = true))
        assertEquals("anb", literalZone("anb", autocorrects = false, leavesAccentsOff = false, blocked = false))
        assertEquals(listOf("and", "an"), notBlocked(listOf("and", "Anb", "an"), setOf("anb")))
        assertEquals(listOf("and", "anb"), notBlocked(listOf("and", "anb"), emptySet()))
    }

    @Test
    fun fuzzyPassStillRescuesTyposUnderCompletions() {
        // t,h,r has no exact path ("thr..." is empty in the small dict), so
        // the fuzzy fallback must still surface "the" as it did before
        // completions.
        val result = predictor.decode(taps("thr"), emptyList())
        assertTrue(words(result).contains("the"))
        assertEquals(
            WordCandidate.Source.FUZZY_TAP,
            result.first { it.word == "the" }.source,
        )
    }
}
