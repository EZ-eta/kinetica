package com.kinetica.keyboard.ime

import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.keys.AutoCapitalization
import kotlin.math.roundToInt

/**
 * How a word ends: finalized at a delimiter with the tap autocorrect, committed with its correction
 * strip, recent column and lesson, picked from the bar, or abandoned. Each commit also feeds the
 * spacebar's speed and, with no primary language, the language momentum.
 */
internal class CommitPath(private val ime: KineticaIME) {

    /** The spacebar's words per minute (TypingSpeed), and the touch span of the word in hand. */
    private val typingSpeed = TypingSpeed()
    private var wordStartMs = -1L
    private var wordEndMs = -1L
    private val speedHideRunnable = Runnable { ime.keyboardView?.speedLabel = null }

    /** A gesture of the word in hand: its touch span counts toward the word's speed. */
    fun noteTouch(token: InputToken) {
        if (wordStartMs < 0) wordStartMs = token.tStart
        wordEndMs = token.tEnd
    }

    /**
     * Called before any delimiter. Applies tap autocorrect when the literal
     * word is unknown and the best candidate is geometrically confident.
     * Returns true when a pending word was committed.
     */
    fun finalizePendingWord(): Boolean {
        // The offer's seeded word is not a word in progress and must never be committed.
        if (ime.wordState.midWordOffer != null) {
            abandonWord()
            return false
        }
        val comp = ime.composer ?: run {
            abandonWord()
            return false
        }
        if (!comp.hasPendingWord) {
            // A delimiter says the user has moved on from a kept bar's words.
            if (ime.wordState.keptBar != null) abandonWord()
            return false
        }

        var finalWord = ime.wordState.tentativeWord
        val p = ime.residents.predictor
        val threshold = ime.config.autocorrectConfidence
        if (p != null && !comp.hasSwipeToken() && ime.wordState.lastLiteral.isNotEmpty() &&
            !ime.editorState.privateMode
        ) {
            val target = if (threshold != null) {
                p.tapAutocorrect(ime.wordState.lastLiteral, ime.wordState.lastTentative, threshold)
            } else {
                null
            }
            if (target != null) {
                val display = ime.displayWord(target.word)
                val typed = ime.wordState.tentativeWord
                ime.replaceTentative(display)
                finalWord = display
                ime.backspace.autocorrectUndo = AutocorrectUndo(typed, display, ime.cursorExpected())
            } else if (threshold != null && !ime.editorState.addressField) {
                // A learned capital, as autocorrect gives a German noun its own: the letters
                // spell a word, so autocorrect leaves them, and the capital is the user's.
                learnedCapital(finalWord, ime.casesFor(ime.languageOf(finalWord))[finalWord.lowercase()])?.let { capital ->
                    val typed = ime.wordState.tentativeWord
                    ime.replaceTentative(capital)
                    finalWord = capital
                    ime.backspace.autocorrectUndo = AutocorrectUndo(typed, capital, ime.cursorExpected())
                    DecodeTrace.log { "  learned capital typed=$typed -> $capital" }
                }
            }
        }
        // English's lone "i". Nothing upstream can reach it: letters are
        // committed one at a time before there is a word to look at, so the
        // shift state is positional only, and autocorrect never rewrites a word
        // the dictionary already has. Applies to the tap path here and to
        // decoded words through displayWord.
        val cased = AutoCapitalization.forWord(finalWord, ime.config.language, ime.languageOf(finalWord))
        if (cased != finalWord) {
            ime.replaceTentative(cased)
            finalWord = cased
        }
        commitWordInternal(finalWord)
        return true
    }

    private fun commitWordInternal(word: String) {
        recordTypingSpeed(word)
        noteLanguageMomentum(word)
        // Correction options, each a tappable zone: the committed word, the other ranked
        // candidates, then the literal tap string (the way back from a wrong autocorrect).
        val options = ArrayList<String>(KineticaConstants.TOP_K + 1)
        options.add(word)
        for (c in ime.wordState.lastCandidates) {
            val d = ime.displayWord(c.word)
            if (!options.contains(d)) options.add(d)
        }
        var typed: String? = null
        if (ime.wordState.lastLiteral.isNotEmpty()) {
            val d = ime.displayWord(ime.wordState.lastLiteral)
            if (!options.contains(d)) options.add(d)
            if (!d.equals(word, ignoreCase = true)) typed = d
        }
        // The word itself stays first; a blocked one among the others is never offered back.
        if (ime.residents.blockedSpellings.isNotEmpty()) {
            options.subList(1, options.size).removeAll { it.lowercase() in ime.residents.blockedSpellings }
            if (typed?.lowercase() in ime.residents.blockedSpellings) typed = null
        }
        val lang = ime.languageOf(word)
        val start = ime.cursorExpected() - word.length
        if (word.isNotEmpty()) {
            val alts = options.drop(1).let { o -> if (typed == null) o else listOf(typed) + (o - typed) }
            val langs = ime.wordState.candidateLanguages + (typed?.let { mapOf(it.lowercase() to lang) } ?: emptyMap())
            ime.commitHistory.record(start, word, alts, langs)
        }
        // Read before the candidates go: what the keyboard proposed for these letters, and the
        // text before the word (the editor holds the word, so it is cut off the read).
        val proposal = ime.wordState.lastCandidates.firstOrNull { it.word.equals(word, ignoreCase = true) }?.word
        val textBefore = if (ime.editorState.addressField || ime.editorState.teachesNothing) {
            null
        } else {
            ime.ich.textBeforeCursor(word.length + KineticaIME.CAPS_LOOKBACK_CHARS)?.toString()
                ?.takeIf { it.endsWith(word) }?.dropLast(word.length)
        }
        ime.composer?.commitWord(word.lowercase())
        ime.wordState.reopenedContext = null
        ime.wordState.tentativeLength = 0
        ime.wordState.tentativeWord = ""
        ime.wordState.lastCandidates = emptyList()
        ime.wordState.lastTentative = null
        ime.wordState.keptBar = null
        // The strip outlives the candidate list, so provenance is snapshotted, not cleared:
        // a correction pick is a real commit.
        ime.wordState.correctionLanguages = ime.wordState.candidateLanguages
        ime.wordState.candidateLanguages = emptyMap()
        ime.wordState.lastLiteral = ""
        ime.suggestionBar?.clearSuggestions()
        var lesson: CommitLesson? = null
        if (word.isNotEmpty() && !ime.editorState.teachesNothing) {
            val reopened = ime.wordState.reopenedLesson
            reopened?.let { if (takesBackReopened(it.word, word)) ime.learner.takeBack(it) }
            // Every commit (top prediction, tapped correction or manual typing) is one unit of
            // personal evidence, filed to the dictionary the word came from by its per-word
            // provenance. The one commit that learns nothing is a swipe whose full-buffer
            // decode gave no auto-committable word: the screen then shows a stale partial decode.
            // A reopened commit committed again unchanged stays learned once: backspaced into and
            // retyped, the reload's letters are not the word, so learnsOnCommit alone counts it twice.
            if (!ime.wordState.swipeDecodeEmpty && learnsOnCommit(word, ime.wordState.reloadedWord) && !keepsReopened(reopened?.word, word)) {
                val filed = ime.sharedFiling.languageFor(ime.heldBy(word), lang)
                val counted = ime.learner.learnWord(word, lang = filed)
                val held = ime.predictorFor(filed)?.holdsExactSpelling(word) == true
                val caseBefore = ime.casesFor(filed)[word.lowercase()]
                val vote = caseVote(word, proposal, held, textBefore, caseBefore?.display, filed)
                if (vote.vote == 0 && word.any { it.isUpperCase() }) {
                    DecodeTrace.log { "  learn capital none word=$word why=${vote.why} lang=$filed" }
                }
                val voted = ime.learner.voteCase(word, filed, vote)
                ime.learner.lastLearnedWord = word.lowercase()
                ime.learner.lastLearnedWordLang = filed
                // The pair is learned from the same evidence as the word, under the same
                // guards. composer.commitWord has already pushed, so the predecessor is the
                // second-from-last context entry; lastCommit is emptied on every field change.
                val pair = ime.learner.learnPair(ime.composer?.contextSnapshot(), word, filed)
                lesson = CommitLesson(word.lowercase(), filed, counted, voted, caseBefore, pair)
            }
        }
        if (word.isNotEmpty()) ime.commitMarks.record(start, word, lesson)
        // Not under the learning guard above, and not the same condition: see
        // showsCorrectionStrip. With recent words on, the alternatives are already in the
        // word's column, so the strip gives the bar back to next-word predictions.
        val strip = showsCorrectionStrip(word, ime.editorState.offersCorrections, options.size) && !ime.config.recentWords
        if (word.isNotEmpty()) ime.lastCommit.onCommit(word, strip, options.drop(1), ime.wordState.correctionLanguages, typed)
        // The letters as typed lead a corrected word's column: the strip's way back from a wrong
        // autocorrect, which the recent column replaces when it is on.
        if (word.isNotEmpty()) {
            val alts = options.drop(1).let { o -> if (typed == null) o else listOf(typed) + (o - typed) }
            ime.barContents.recentWords.onCommit(word, alts, ime.wordState.correctionLanguages + (typed?.let { mapOf(it.lowercase() to lang) } ?: emptyMap()))
        }
        ime.backspace.target = null
        ime.barContents.besideShown = false
        if (strip) {
            ime.barContents.stripOptions = options.take(KineticaConstants.TOP_K)
            ime.suggestionBar?.showCorrection(ime.barContents.stripOptions.map { ime.barContents.barSuggestion(it) }, selected = 0)
        }
        ime.wordState.swipeDecodeEmpty = false
        ime.wordState.reloadedWord = null
        ime.wordState.reopenedLesson = null
        ime.gestureUndo.clear()
        ime.barContents.updateBarWordPending()
        ime.wordEnded()
    }

    fun abandonWord() {
        ime.wordState.keptBar = null
        ime.wordState.midWordOffer = null
        ime.wordState.midWordHistory = null
        wordStartMs = -1L
        ime.expansions.choice = null
        ime.composer?.clear()
        ime.wordState.reloadedWord = null
        ime.wordState.reopenedLesson = null
        ime.gestureUndo.clear()
        ime.wordState.seededWithoutTokens = false
        ime.wordState.tentativeLength = 0
        ime.wordState.tentativeWord = ""
        ime.wordState.lastCandidates = emptyList()
        ime.wordState.lastTentative = null
        ime.wordState.candidateLanguages = emptyMap()
        ime.wordState.lastLiteral = ""
        ime.wordState.swipeDecodeEmpty = false
        ime.suggestionBar?.clearSuggestions()
        ime.barContents.clearCorrection()
        ime.barContents.updateBarWordPending()
        ime.wordEnded()
    }

    /**
     * Ends the buffer as the stale timeout always has, but leaves its candidates up.
     *
     * A populated bar with nothing auto-committed is merge's `no-native` return: the active
     * language decoded nothing and another did. Those are the words the user is reading, so
     * they stay pickable. The buffer still closes, so the next gesture starts a word,
     * and provenance stays, so a pick is learned into the language it came from.
     */
    fun closeBufferKeepBar() {
        val tail = ime.ich.textBeforeCursor(KEPT_BAR_TAIL_CHARS)?.toString()
        if (tail == null) {
            abandonWord()
            return
        }
        ime.wordState.keptBar = KeptBar(ime.wordState.tentativeWord, tail)
        ime.composer?.clear()
        ime.wordState.reloadedWord = null
        ime.wordState.reopenedLesson = null
        ime.gestureUndo.clear()
        ime.wordState.seededWithoutTokens = false
        ime.wordState.tentativeLength = 0
        ime.wordState.tentativeWord = ""
        ime.wordState.lastTentative = null
        ime.wordState.lastLiteral = ""
        ime.wordState.swipeDecodeEmpty = false
        ime.barContents.clearCorrection()
        ime.barContents.updateBarWordPending()
        ime.wordEnded()
        DecodeTrace.log { "  stale keep n=${ime.wordState.lastCandidates.size} staleLen=${ime.wordState.keptBar?.staleWord?.length}" }
    }

    fun onSuggestionPicked(word: String) {
        ime.autospace.cancel()
        ime.wordState.midWordOffer?.let {
            ime.reopen.pickMidWord(it, word)
            return
        }
        val choice = ime.expansions.choice
        if (choice != null) {
            ime.expansions.choice = null
            val i = choice.shown.indexOf(word)
            if (i >= 0 && ime.suggestionBar?.showsWords(choice.shown) == true) {
                ime.expansions.pickExpansion(choice, i)
                return
            }
        }
        if (ime.barContents.barPredictions.isNotEmpty() && ime.wordState.keptBar == null && ime.wordState.tentativeLength == 0) {
            // The pair is learned from the word on screen, not from whatever the composer last
            // committed: a cursor move can put a prediction after any word.
            previousWordForPrediction(ime.ich.textBeforeCursor(BarContents.PREDICT_TAIL_CHARS))
                ?.let { ime.composer?.anchorContext(it) }
            ime.barContents.barPredictions = emptyList()
            DecodeTrace.log { "  predict pick word=$word" }
        }
        val kept = ime.wordState.keptBar
        if (kept != null) {
            ime.wordState.keptBar = null
            val now = ime.ich.textBeforeCursor(KEPT_BAR_TAIL_CHARS)?.toString() ?: ""
            val span = keptBarPickSpan(now, kept.tailAtClose, kept.staleWord)
            DecodeTrace.log { "  stale pick word=$word span=${if (span < 0) "refused" else span}" }
            if (span < 0) {
                abandonWord()
                return
            }
            // What replaceTentative replaces: the earlier decode the closed buffer left on
            // screen, re-proved against the editor, not remembered.
            ime.wordState.tentativeLength = span
            ime.wordState.tentativeWord = kept.staleWord
        }
        ime.replaceTentative(word)
        commitWordInternal(word)
        // Tidy spaces holds a pick's space as it holds the autospace: a word picked in front of
        // `!` gains no space before it.
        val after = if (ime.config.tidySpaces) ime.ich.textAfterCursor(2) else null
        if (spacesAfterPick(ime.editorState.addressField) && autospaceWanted(ime.config.tidySpaces, after, ime.config.noSpaceBefore)) {
            ime.commitMarks.spaceAt(ime.cursorExpected())
            ime.commitTracked(" ")
            // A picked word's space is the keyboard's own, like the autospace, so punctuation
            // takes it back. commitTracked clears the flag, so this comes after it.
            ime.autospace.inserted = true
        }
        ime.updateAutoShift()
        // After the space, so the next word's predictions have a word to follow.
        ime.barContents.refreshPredictions()
    }

    fun onCorrectionPicked(replacement: String) {
        val current = ime.lastCommit.stripWord ?: return
        // The strip outlives the commit it names, and commitWordInternal records the
        // commit inside its own learning guard, so the editor can have moved on
        // from the word this is about. A refusal costs one tap; counting back a remembered
        // length ate real text.
        val before = ime.ich.textBeforeCursor(current.length + KineticaIME.COMMIT_TAIL_CHARS) ?: ""
        val span = commitSpan(before, current, KineticaIME.COMMIT_TAIL_CHARS)
        if (span < 0) {
            DecodeTrace.log { "  correction refused word=$current" }
            return
        }
        ime.autospace.cancel()
        val tail = before.subSequence(before.length - span + current.length, before.length)
        ime.commitHistory.replaced(ime.cursorExpected() - span, current, replacement)
        ime.ich.replaceBeforeCursor(span, replacement + tail)
        ime.expectAfter(span, replacement.length + tail.length, rewritesWord = true)
        ime.lastCommit.onReplaced(replacement)
        ime.barContents.recentWords.onReplaced(0, replacement)
        // Same transfer the unigram counts get below: the pair the wrong commit recorded
        // is taken back and the corrected one recorded in its place.
        val prevForPair = ime.composer?.contextSnapshot()?.getOrNull(
            (ime.composer?.contextSnapshot()?.size ?: 0) - 2,
        )
        ime.learner.unlearnLastPair()
        ime.forgetCommitMarks()
        ime.composer?.replaceLastCommit(replacement.lowercase())
        val replacementLang = ime.sharedFiling.languageFor(ime.heldBy(replacement), ime.languageOf(replacement))
        if (prevForPair != null) {
            ime.learner.learnPair(listOf(prevForPair, replacement.lowercase()), replacement, replacementLang)
        }
        // The tapped word is the real final commit: it earns the weight, and
        // the replaced word hands back the count the unwanted commit earned.
        ime.learner.learnWord(replacement, lang = replacementLang)
        if (!current.equals(replacement, ignoreCase = true)) {
            ime.learner.unlearnWord(current)
        } else if (current != replacement) {
            // A case-only pick is a choice of case: down for the lowercase, up for a capital the
            // dictionary does not hold as written.
            val held = ime.predictorFor(replacementLang)?.holdsExactSpelling(replacement) == true
            val vote = when {
                replacement == replacement.lowercase() -> CaseVote(-1, null)
                held || replacement.none { it.isLowerCase() } -> CaseVote.NONE
                else -> CaseVote(1, replacement)
            }
            ime.learner.voteCase(replacement, replacementLang, vote)
        }
        ime.learner.lastLearnedWord = replacement.lowercase()
        ime.learner.lastLearnedWordLang = replacementLang
        // Beside the next words, the first zone now holds the word replaced and the next words
        // follow the new one; the full strip is left as it is.
        if (ime.config.nextWordBeside) ime.barContents.refreshPredictions()
    }

    /** A new field starts the spacebar's speed afresh, its label gone. */
    fun clearTypingSpeed() {
        typingSpeed.clear()
        ime.mainHandler.removeCallbacks(speedHideRunnable)
        ime.keyboardView?.speedLabel = null
    }

    /**
     * Feeds the spacebar's speed with the word just committed. Only a word with real touches
     * behind it counts: a pick of a predicted word has none, and its time would be the
     * user's reading, not their typing.
     */
    private fun recordTypingSpeed(word: String) {
        val start = wordStartMs
        wordStartMs = -1L
        if (!ime.config.typingSpeed || ime.editorState.privateMode || start < 0 || word.isEmpty()) return
        val wpm = typingSpeed.logWord(start, wordEndMs, word.length)
        DecodeTrace.log { "  wpm display=${wpm?.let { "%.1f".format(it) }} rate=${typingSpeed.rate()?.let { "%.1f".format(it) }} letters=${word.length}" }
        val kv = ime.keyboardView ?: return
        kv.speedLabel = wpm?.let { ime.getString(R.string.typing_speed_label, it.roundToInt()) }
        ime.mainHandler.removeCallbacks(speedHideRunnable)
        ime.mainHandler.postDelayed(speedHideRunnable, TypingSpeed.IDLE_BREAK_MS)
    }

    /**
     * Moves each resident language's standing toward the word just committed, and hands the
     * composer the new weights. A word a language does not hold scores zero there.
     */
    private fun noteLanguageMomentum(word: String) {
        if (!ime.config.noPrimary || word.isEmpty() || ime.editorState.teachesNothing) return
        val scores = HashMap<String, Float>(3)
        for ((lang, p) in listOf(ime.config.language to ime.residents.predictor, ime.residents.secondaryLanguage to ime.residents.secondaryPredictor, ime.residents.extraLanguage to ime.residents.extraPredictor)) {
            if (lang == null || p == null) continue
            scores[lang] = if (p.isWordIgnoringCase(word)) p.frequencyByte(word).coerceAtLeast(0) / 255f else 0f
        }
        if (scores.size < 2) return
        ime.momentum.observe(scores)
        val weights = ime.momentum.weightsFor(ime.residentLanguages())
        ime.composer?.languageWeights = weights
        DecodeTrace.log { "  momentum word=$word front=${ime.momentum.front(ime.residentLanguages())} w=${weights.entries.joinToString(",") { "${it.key}:${"%.2f".format(it.value)}" }}" }
    }

    companion object {
        // How much editor text a kept bar compares to prove nothing changed before a pick.
        // Longer than any word, so a space or a deleted letter after the stale text shows.
        const val KEPT_BAR_TAIL_CHARS = 64
    }
}
