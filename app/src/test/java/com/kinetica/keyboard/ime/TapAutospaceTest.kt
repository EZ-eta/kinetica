package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.StandaloneLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions behind autospacing a tapped word, pure like hugsPreviousWord and
 * startsNewSentence because the service around them has no JVM reach.
 */
class TapAutospaceTest {

    /** The predicate with everything at its ordinary value, so each test names one gate. */
    private fun fires(
        enabled: Boolean = true,
        hasSwipeToken: Boolean = false,
        literal: String,
        literalIsWord: Boolean,
        literalIsStandaloneLetter: Boolean = false,
        addressField: Boolean = false,
        joinedToWhatPrecedes: Boolean = false,
        joinedTokenIsWord: Boolean = false,
        joinedByApostrophe: Boolean = false,
        carriesNoToken: Boolean = false,
    ) = autospacesTappedWord(
        enabled, hasSwipeToken, literal, literalIsWord, literalIsStandaloneLetter,
        addressField, joinedToWhatPrecedes, joinedTokenIsWord, joinedByApostrophe,
        carriesNoToken,
    )

    // ------------------------------------------------------- when it fires

    @Test
    fun aTappedWordThatIsAWordEarnsItsSpace() {
        assertTrue(fires(literal = "is", literalIsWord = true))
    }

    @Test
    fun lettersThatSpellNothingNeverFire() {
        // The gate that does the work: mid-word states are the common case, and most of
        // them are not words.
        assertFalse(fires(literal = "carp", literalIsWord = false))
    }

    @Test
    fun aSwipeBearingWordIsNotThisFeature() {
        // It has always had its own timer; running both would arm it twice.
        assertFalse(fires(hasSwipeToken = true, literal = "is", literalIsWord = true))
    }

    @Test
    fun aSingleLetterFiresOnlyWhenItIsAWordInThisLanguage() {
        // One-letter words are 12% of the words in captured prose, so `a` and `I` must space.
        // The gate is StandaloneLetters' curated per-language list, not the dictionary, where
        // every letter a-z is an entry.
        assertTrue(fires(literal = "a", literalIsWord = true, literalIsStandaloneLetter = true))
        // `t` is in en_wordlist at 72 881 and is not a word anyone types alone.
        assertFalse(fires(literal = "t", literalIsWord = true, literalIsStandaloneLetter = false))
    }

    @Test
    fun czechSingleLetterWordsAutospaceWithoutAdmittingDictionaryNoise() {
        // The English fallback only spaces a/i, losing Czech k/o/s/u/v/z.
        // Dictionary membership cannot distinguish these words from initials.
        for (letter in 'a'..'z') {
            assertEquals(
                "Czech '$letter' autospace",
                letter in "aikosuvz",
                fires(
                    literal = letter.toString(),
                    literalIsWord = true,
                    literalIsStandaloneLetter = StandaloneLetters.isWord(letter, "cs"),
                ),
            )
        }
    }

    @Test
    fun aSingleLetterAfterAnApostropheStillRefuses() {
        // The joined branch comes first: the `a` of `dell'anno` follows an apostrophe, so it
        // never reaches the one-letter rule. It was the only premature fire on captured prose.
        assertFalse(
            fires(
                literal = "a", literalIsWord = true, literalIsStandaloneLetter = true,
                joinedToWhatPrecedes = true, joinedByApostrophe = true,
                joinedTokenIsWord = false,
            ),
        )
    }

    @Test
    fun theSwitchOffMeansNothingChanges() {
        assertFalse(fires(enabled = false, literal = "is", literalIsWord = true))
    }

    // --------------------------------------------------- when it is taken back

    @Test
    fun typingOnStraightAfterwardsTakesTheSpaceBack() {
        // "car", pause, space, "pet" -> "carpet".
        assertTrue(retractsAutospace(fromTappedWord = true, tentativeLength = 0, elapsedMs = 120, windowMs = 600, fusedIsPrefix = true))
    }

    @Test
    fun aSwipesSpaceIsNeverRetracted() {
        // There the gesture finished and the space was earned; a following letter is a
        // new word, not a continuation.
        assertFalse(retractsAutospace(fromTappedWord = false, tentativeLength = 0, elapsedMs = 120, windowMs = 600, fusedIsPrefix = true))
    }

    @Test
    fun aWordAlreadyUnderwayIsNotAContinuation() {
        assertFalse(retractsAutospace(fromTappedWord = true, tentativeLength = 3, elapsedMs = 120, windowMs = 600, fusedIsPrefix = true))
    }

    @Test
    fun comingBackLaterStartsANewWord() {
        // Typing `is`, leaving, then typing `land` must not produce `island`.
        assertFalse(retractsAutospace(fromTappedWord = true, tentativeLength = 0, elapsedMs = 5_000, windowMs = 600, fusedIsPrefix = true))
        // ...and the boundary itself belongs to the retraction, not to the new word.
        assertTrue(retractsAutospace(fromTappedWord = true, tentativeLength = 0, elapsedMs = 600, windowMs = 600, fusedIsPrefix = true))
    }

    // --------------------------------------------------- when it never arms at all

    @Test
    fun oneDeleteIsEnoughToStopIt() {
        // Deleting the space reopens the word as tap anchors, which look like a freshly tapped
        // word, so the timer would arm again and `name@mail.com` could not be typed. The reload
        // carries no gesture, which tells them apart; aReloadedWordDoesNotArmTheTimer makes the
        // same claim from the other side.
        assertFalse(fires(literal = "name", literalIsWord = true, carriesNoToken = true))
    }

    @Test
    fun aWordCompletedAfterDeletingItsSpaceEarnsANewOne() {
        // `car`, delete the space, then `pet`: the finished `carpet` is a different, completed
        // word and earns its space. A flag recording the delete used to refuse it, so this
        // asserts the absence of a gate.
        assertTrue(fires(literal = "carpet", literalIsWord = true, carriesNoToken = false))
    }

    @Test
    fun aFreshlySwipedWordAfterASlideEarnsItsSpace() {
        // The other half of the same defect. Sliding a swiped word's space away abandons the
        // word without reloading, so the next gesture is a new word and its space is earned.
        assertTrue(
            autospacesSwipedWord(
                hasSwipeToken = true, addressField = false, carriesNoToken = false,
            ),
        )
    }

    @Test
    fun anAddressFieldNeverArmsIt() {
        assertFalse(fires(literal = "mail", literalIsWord = true, addressField = true))
    }

    @Test
    fun anOrdinaryFieldStillArmsIt() {
        // The gate above is field-shaped, not a general retreat from the feature.
        assertTrue(fires(literal = "mail", literalIsWord = true, addressField = false))
    }

    // ------------------------------------------- joined to what precedes it

    @Test
    fun aWordThatContinuesAnEarlierTokenTakesNoSpace() {
        // A log saved as `notes 14.log`, where only the `14` was typed: `_` finalizes the word,
        // `notes` is in the dictionary, and the space landed before the digits.
        assertFalse(fires(literal = "notes", literalIsWord = true, joinedToWhatPrecedes = true))
    }

    @Test
    fun theUnderscoreCaseAsTheEditorSeesIt() {
        assertTrue(joinsPrecedingToken("session_"))
        assertTrue(joinsPrecedingToken("e-"))
        assertTrue(joinsPrecedingToken("don'"))
        assertTrue(joinsPrecedingToken("example."))
        assertTrue(joinsPrecedingToken("a/"))
        assertTrue(joinsPrecedingToken("user@"))
        // A letter or digit is the plainest case of all: the word is a suffix.
        assertTrue(joinsPrecedingToken("session"))
        assertTrue(joinsPrecedingToken("v2"))
    }

    @Test
    fun ordinaryTypingIsUntouched() {
        // By far the common case.
        assertFalse(joinsPrecedingToken("hello "))
        assertFalse(joinsPrecedingToken("Hi.\t"))
        assertFalse(joinsPrecedingToken("first\n"))
        // Start of the field.
        assertFalse(joinsPrecedingToken(""))
    }

    @Test
    fun anOpeningQuoteOrBracketStillSpaces() {
        // `"hello world"` has to keep its space, so the rule is not "anything that is not
        // whitespace".
        assertFalse(joinsPrecedingToken("\""))
        // The straight apostrophe is not an opener: Italian elision beats quoting.
        assertTrue(joinsPrecedingToken("l'"))
        assertFalse(joinsPrecedingToken("("))
        assertFalse(joinsPrecedingToken("["))
        assertFalse(joinsPrecedingToken("\u00ab"))
        assertFalse(joinsPrecedingToken("\u201c"))
        // Spanish inverted marks, which startsNewSentence already reads as openers.
        assertFalse(joinsPrecedingToken("\u00bf"))
    }

    @Test
    fun theClosingFormsOfThoseMarksDoJoin() {
        // A closing quote right against the word is not an opener, and `don't` is the
        // reason the typographic apostrophe is in the joiner set.
        assertTrue(joinsPrecedingToken("don\u2019"))
    }

    // -------------------------------------- the fused form must still be a word-in-waiting

    @Test
    fun aFinishedWordDoesNotSwallowTheNextOne() {
        // The reported bug: `automatico` finished, the space arrived, and the `p` of `per`
        // took it back, so the buffer became `automaticop` and decoded to nothing.
        assertFalse(
            retractsAutospace(
                fromTappedWord = true, tentativeLength = 0, elapsedMs = 120,
                windowMs = 600, fusedIsPrefix = false,
            ),
        )
    }

    @Test
    fun aRealContinuationIsStillTakenBack() {
        // What the retraction is for, and both cases its KDoc names: a premature space
        // inside `automatico`, and `is` + `land`. Both fuse into something that can still
        // become a word, so both still retract.
        assertTrue(
            retractsAutospace(
                fromTappedWord = true, tentativeLength = 0, elapsedMs = 120,
                windowMs = 600, fusedIsPrefix = true,
            ),
        )
    }

    @Test
    fun theWordTheSpaceWouldBeTakenBackIntoIsReadFromTheEditor() {
        assertEquals("automatico", wordBeforeAutospace("con lo spazio automatico "))
        assertEquals("l'altro", wordBeforeAutospace("l'altro "))
        // No space at the cursor: nothing to retract, so nothing to fuse into.
        assertEquals("", wordBeforeAutospace("automatico"))
        assertEquals("", wordBeforeAutospace(""))
        // Punctuation before the space is not part of the word.
        assertEquals("", wordBeforeAutospace("done. "))
        // Start of the field.
        assertEquals("ciao", wordBeforeAutospace("ciao "))
    }

    @Test
    fun aShortFirstWordCanStillFuseAndThatIsWhatTheWindowIsFor() {
        // The residue: `la` + `g` is `lag`, a live prefix of `lago`, so the gate passes it and
        // `la gente` can still fuse. On one prose capture nothing of six letters or more fuses,
        // 2 of 16 at four to five, and most two- and three-letter words do; the window bounds
        // the rest.
        assertTrue(
            retractsAutospace(
                fromTappedWord = true, tentativeLength = 0, elapsedMs = 120,
                windowMs = 600, fusedIsPrefix = true,
            ),
        )
    }

    // ------------------------- the reopened word must land before what reopened it

    @Test
    fun aReopenedWordIsStampedBeforeTheLetterThatReopenedIt() {
        // The letter that triggers the reload carries its real touch time. Anchors based on
        // `now` land after it whenever touch-to-reload latency exceeds the word's length in ms,
        // so the new letter sorts before the whole reloaded word: `automatico` + `p` reached the
        // decoder as `pautomatico`.
        val touch = 10_000L
        val base = reloadAnchorBase(touch, count = 10)
        assertTrue("first anchor must precede the touch", base < touch)
        assertTrue("last anchor must precede the touch", base + 9 < touch)
    }

    @Test
    fun aOneLetterReloadStillLeavesRoom() {
        val touch = 10_000L
        assertTrue(reloadAnchorBase(touch, count = 1) + 0 < touch)
    }

    @Test
    fun aLongWordDoesNotOverrunTheTouchThatReopenedIt() {
        // MAX_WORD_LEN is the worst case the reload can be handed.
        val touch = 10_000L
        val n = com.kinetica.keyboard.engine.KineticaConstants.MAX_WORD_LEN
        assertTrue(reloadAnchorBase(touch, n) + (n - 1) < touch)
    }

    // ------------------------ a joined token that is itself a word still earns its space

    @Test
    fun aContractionAutospacesAlthoughTheComposerOnlySawItsTail() {
        // `don't` reaches the composer as `don`, then a one-letter `t`, because the apostrophe
        // routes to onPunctuation and finalizes the word. The literal alone can never earn the
        // space.
        assertTrue(
            fires(
                literal = "t", literalIsWord = false,
                joinedToWhatPrecedes = true, joinedTokenIsWord = true,
            ),
        )
    }

    @Test
    fun anUnfinishedTokenStillTakesNoSpace() {
        // `session_notes` before the `14`: joined to what precedes, and the whole token
        // is not a word either. This is the case the joiner rule was built for.
        assertFalse(
            fires(
                literal = "notes", literalIsWord = true,
                joinedToWhatPrecedes = true, joinedTokenIsWord = false,
            ),
        )
    }

    @Test
    fun theLengthRuleStillGovernsAnUnjoinedWord() {
        // The override applies only where the joiner rule bit. A plain one-letter word is
        // refused as before, and the joined-token flag cannot rescue it.
        assertFalse(
            fires(
                literal = "a", literalIsWord = true,
                joinedToWhatPrecedes = false, joinedTokenIsWord = true,
            ),
        )
    }

    @Test
    fun theWholeEditorTokenIsWhatGetsLookedUp() {
        assertEquals("don't", joinedTokenForAutospace("I don't"))
        assertEquals("d'accordo", joinedTokenForAutospace("sono d'accordo"))
        assertEquals("log-12.com", joinedTokenForAutospace("visit log-12.com"))
        assertEquals("example.com", joinedTokenForAutospace("example.com"))
        // Stops at whitespace, and at anything that neither joins nor spells.
        assertEquals("world", joinedTokenForAutospace("hello world"))
        assertEquals("hello", joinedTokenForAutospace("(hello"))
        // Joiners with no letter in them spell nothing.
        assertEquals("", joinedTokenForAutospace("12"))
        assertEquals("", joinedTokenForAutospace("--"))
        assertEquals("", joinedTokenForAutospace(""))
    }

    // ------------------------------- an elision spaces on the piece after the apostrophe

    @Test
    fun anElidedWordAutospacesOnItsSecondHalf() {
        // Chosen over the quoting case: an
        // apostrophe joiner whose whole token is not a word falls back to judging the
        // piece after it. `dell'anno`, `d'accordo`, `un'ora`, `nell'immagine` are all
        // absent from it_wordlist.txt, so the joined lookup can never find them; `anno`,
        // `accordo`, `ora` and `immagine` are ordinary entries.
        assertTrue(
            fires(
                literal = "anno", literalIsWord = true,
                joinedToWhatPrecedes = true, joinedTokenIsWord = false,
                joinedByApostrophe = true,
            ),
        )
    }

    @Test
    fun theFallbackIsForTheApostropheAlone() {
        // Behaviour to keep. `log-12.com` joins on `.`, and `12` is
        // no word, so the token lookup is the only question asked and it answers no. Same
        // for `notes_14.log` and `name@mail.com`.
        assertFalse(
            fires(
                literal = "com", literalIsWord = true,
                joinedToWhatPrecedes = true, joinedTokenIsWord = false,
                joinedByApostrophe = false,
            ),
        )
    }

    @Test
    fun aContractionStillGoesThroughTheJoinedLookup() {
        // `don't` and `it's` are in en_wordlist with large counts, so the whole token is a
        // word and the first branch answers before the fallback is reached. That matters
        // because their tails, `t` and `s`, would fail the length rule outright.
        assertTrue(
            fires(
                literal = "t", literalIsWord = false,
                joinedToWhatPrecedes = true, joinedTokenIsWord = true,
                joinedByApostrophe = true,
            ),
        )
    }

    @Test
    fun theLengthRuleStillGovernsTheTail() {
        // A one-letter tail earns nothing on its own: the fallback lends the elision the
        // ordinary tap rule, not a shorter one. `c'e` gets no space, and
        // `c'\u00e8` would need the joined lookup, the wordlist, to find it.
        assertFalse(
            fires(
                literal = "e", literalIsWord = true,
                joinedToWhatPrecedes = true, joinedTokenIsWord = false,
                joinedByApostrophe = true,
            ),
        )
    }

    @Test
    fun whichJoinerItIsIsReadFromTheEditor() {
        assertTrue(joinedByApostrophe("dell'"))
        assertTrue(joinedByApostrophe("don'"))
        // The typographic form too: the symbols layer offers it as an alternate.
        assertTrue(joinedByApostrophe("don\u2019"))
        assertFalse(joinedByApostrophe("session_"))
        assertFalse(joinedByApostrophe("example."))
        assertFalse(joinedByApostrophe("user@"))
        assertFalse(joinedByApostrophe("e-"))
        assertFalse(joinedByApostrophe("hello "))
        assertFalse(joinedByApostrophe(""))
    }

    @Test
    fun theRetractionAsksAboutTheTailToo() {
        // `dell'ann` earns a space because `ann` is an entry, so the `o` has to take it back,
        // and isLivePrefix("dell'anno") answers no because no elided form is in the trie.
        // Asking about the tail makes the fusion answerable.
        assertEquals("ann", tailAfterLastApostrophe("dell'ann"))
        assertEquals("al", tailAfterLastApostrophe("l'al"))
        assertEquals("s", tailAfterLastApostrophe("it's"))
        assertEquals("ann", tailAfterLastApostrophe("dell\u2019ann"))
        // No apostrophe means no second question; the whole run has already been asked.
        assertEquals("", tailAfterLastApostrophe("automatico"))
        assertEquals("", tailAfterLastApostrophe(""))
        // A trailing apostrophe leaves nothing to ask about.
        assertEquals("", tailAfterLastApostrophe("dell'"))
    }

    // ------------------------------------------------- the re-fire, in both its shapes

    @Test
    fun aReloadedWordDoesNotArmTheTimer() {
        // reloadWordUnderCursor seeds synthetic anchors and seed decodes them, so
        // the result looks like a thumb's, but a word the user parked a cursor in is not
        // finished. On one capture 15 of 57 fires were this.
        assertFalse(fires(literal = "dell", literalIsWord = true, carriesNoToken = true))
    }

    @Test
    fun aRealTokenAfterTheReloadArmsItAgain() {
        // The flag is about the decode, not the word: extending a reopened word is what the
        // reopen is for, and the letter that extends it is evidence of its own.
        assertTrue(fires(literal = "dell", literalIsWord = true, carriesNoToken = false))
    }

    @Test
    fun theSwipePathKeepsItsTwoGates() {
        // An address field arms nothing on either path, and a word with no swipe in it is
        // the tap predicate's business.
        assertFalse(
            autospacesSwipedWord(
                hasSwipeToken = true, addressField = true, carriesNoToken = false,
            ),
        )
        assertFalse(
            autospacesSwipedWord(
                hasSwipeToken = false, addressField = false, carriesNoToken = false,
            ),
        )
    }

    // --------------------------------- one letter waits longer than a word

    @Test
    fun oneLetterWaitsLongerThanAWord() {
        // The longer wait makes the one-letter rule affordable: `a` is a word and also the
        // first letter of `and`, `arrivato` and `ad`, and only silence tells them apart. At a
        // 204 ms slider, 10 of 28 risky word-starts on captured prose fire early.
        assertEquals(300L, singleLetterDelayMs(tapDelayMs = 204, floorMs = 300))
        assertEquals(300L, singleLetterDelayMs(tapDelayMs = 300, floorMs = 300))
    }

    @Test
    fun aRaisedWordDelayWinsOverTheFloor() {
        // A floor, not a fixed value: someone who set the word delay to 600 ms meant it,
        // and a single letter must never be quicker to space than a whole word.
        assertEquals(600L, singleLetterDelayMs(tapDelayMs = 600, floorMs = 300))
        assertEquals(800L, singleLetterDelayMs(tapDelayMs = 800, floorMs = 300))
    }
}
