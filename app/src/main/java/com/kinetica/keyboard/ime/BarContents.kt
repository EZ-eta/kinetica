package com.kinetica.keyboard.ime

import android.util.Log
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.AccentFolder
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.keys.AutoCapitalization
import com.kinetica.keyboard.ui.BarAdjust
import com.kinetica.keyboard.ui.SuggestionBarView

/**
 * What the suggestion bar shows when it is not the decode's own list: next words, the recent words'
 * columns, the correction strip and its alternatives beside the next words, and a retype's offer.
 * The bar's own gestures land here too: the weight slide, the block, a recent word's swap.
 */
internal class BarContents(private val ime: KineticaIME) {

    /**
     * Tells the bar whether a word is in progress, which suppresses the shortcut row. Called
     * from the paths that move word state, not inferred from which setter ran last: a state
     * machine built from setter side effects breaks when the setters reorder.
     */
    fun updateBarWordPending() {
        ime.suggestionBar?.wordPending =
            ime.wordState.tentativeLength > 0 || ime.composer?.hasPendingWord == true
        refreshPredictions()
    }

    /** What the idle bar is offering as next-word predictions; empty when it offers none. */
    var barPredictions: List<WordPredictor.NextWord> = emptyList()

    /** The last few commits and what each beat, for the recent-words bar. */
    val recentWords = RecentWords(RECENT_WORDS_KEPT)

    // The strip's words as the commit showed them, and whether the bar shows its one alternative
    // beside the next words instead; see showBeside.
    var stripOptions: List<String> = emptyList()
    var besideShown = false

    /**
     * Puts the recent words the editor still holds on the bar, oldest first, or takes them
     * down. The correction strip already shows the newest commit, so that one is left out
     * while it is up.
     */
    fun pushRecent() {
        val bar = ime.suggestionBar ?: return
        val entries = recentWords.entries()
        // With a selection up, the text before the cursor is not what the columns name, and a
        // rewrite would replace the selection too (the reason the strip clears there).
        if (!ime.config.recentWords || ime.editorState.privateMode || entries.isEmpty() || ime.selectionLength() != 0) {
            bar.recent = emptyList()
            return
        }
        val before = ime.ich.textBeforeCursor(RECENT_TAIL_CHARS) ?: ""
        val aligned = alignRecent(before, ime.wordState.tentativeLength, entries.map { it.word })
        val skip = if (ime.lastCommit.stripWord != null) 1 else 0
        val cols = ArrayList<SuggestionBarView.RecentColumn>(RECENT_BAR_COLUMNS)
        for (back in skip until minOf(aligned.size, skip + RECENT_BAR_COLUMNS)) {
            val e = entries[entries.size - 1 - back]
            val shown = before.subSequence(before.length - aligned[back], before.length - aligned[back] + e.word.length)
            val alts = notBlocked(sameLanguage(e.alternatives, e.languages, e.word, ime.config.language), ime.residents.blockedSpellings)
            cols.add(
                0,
                SuggestionBarView.RecentColumn(
                    shown.toString(),
                    alts.getOrNull(0)?.let { recentAlternate(shown, it, e.languages) },
                    alts.getOrNull(1)?.let { recentAlternate(shown, it, e.languages) },
                    back,
                    // The counts behind the badges and the slide, each in its own language.
                    listOf(alts.getOrNull(0), e.word, alts.getOrNull(1)).map { w ->
                        if (w == null) 0 else ime.countsFor(e.languages[w.lowercase()] ?: ime.languageOf(w))[w.lowercase()] ?: 0
                    },
                ),
            )
        }
        bar.recent = cols
    }

    /** A recent word's alternative as its column shows it: cased like the word, then the pronoun rule. */
    private fun recentAlternate(written: CharSequence, alt: String, languages: Map<String, String>): String =
        AutoCapitalization.forWord(matchCase(written, alt), ime.config.language, languages[alt.lowercase()] ?: ime.config.language)

    /**
     * Swaps a recent word for one it beat, where the editor still holds it: the text from the
     * word to the cursor is rewritten with the word replaced, as a correction pick rewrites the
     * last one. The word in progress, if any, is part of that tail and comes back unchanged.
     */
    fun onRecentPicked(back: Int, word: String) {
        if (ime.selectionLength() != 0) {
            pushRecent()
            return
        }
        val entries = recentWords.entries()
        val before = ime.ich.textBeforeCursor(RECENT_TAIL_CHARS) ?: ""
        val aligned = alignRecent(before, ime.wordState.tentativeLength, entries.map { it.word })
        if (back !in aligned.indices) {
            DecodeTrace.log { "  recent refused back=$back" }
            pushRecent()
            return
        }
        val entry = entries[entries.size - 1 - back]
        val span = aligned[back]
        val start = before.length - span
        val replacement = matchCase(before.subSequence(start, start + entry.word.length), word)
        val rewritten = recentRewrite(before, span, entry.word.length, replacement)
        ime.commitHistory.replaced(ime.cursorExpected() - span, entry.word, replacement)
        ime.forgetCommitMarks()
        ime.ich.replaceBeforeCursor(span, rewritten)
        ime.expectAfter(span, rewritten.length, rewritesWord = true)
        recentWords.onReplaced(back, replacement)
        if (back == 0) ime.lastCommit.onReplaced(replacement)
        ime.composer?.replaceCommit(back, entry.word.lowercase(), replacement.lowercase())
        DecodeTrace.log { "  recent pick back=$back old=${entry.word} new=$replacement" }
        // The swap is the user's correction, so the weight moves as a correction pick's does,
        // and the pairs with its neighbours move with it.
        val lang = ime.sharedFiling.languageFor(ime.heldBy(replacement), entry.languages[word.lowercase()] ?: ime.languageOf(replacement))
        ime.learner.learnWord(replacement, lang = lang)
        if (!entry.word.equals(replacement, ignoreCase = true)) ime.learner.unlearnWord(entry.word)
        // A neighbour counts only across whitespace: `end. Next` is no pair, as learnPair agrees.
        val prev = if (back + 1 < aligned.size) {
            val p = entries[entries.size - 2 - back].word
            val gap = before.subSequence(before.length - aligned[back + 1] + p.length, start)
            p.takeIf { gap.isNotEmpty() && gap.all { it == ' ' } }
        } else {
            null
        }
        val next = if (back >= 1) {
            val nextStart = before.length - aligned[back - 1]
            val gap = before.subSequence(start + entry.word.length, nextStart)
            entries[entries.size - back].word.takeIf { gap.isNotEmpty() && gap.all { it == ' ' } }
        } else {
            null
        }
        // The pairs taken back were learned with the old word, in its language.
        val oldLang = entry.languages[entry.word.lowercase()] ?: ime.languageOf(entry.word)
        for (m in recentPairMoves(prev, entry.word, replacement, next)) {
            ime.learner.adjustPair(m.prev, m.word, pairMoveLanguage(m, oldLang, lang), m.delta)
        }
        pushRecent()
    }

    /**
     * Fills the idle bar with what usually follows the word before the cursor, or takes the
     * predictions down once the bar is not idle. A word in progress, its candidates, the
     * correction strip and a kept bar all come first, in that order.
     */
    fun refreshPredictions() {
        // An offer of expansion targets lasts while the bar shows it, not until a pick.
        ime.expansions.choice?.let { if (ime.suggestionBar?.showsWords(it.shown) != true) ime.expansions.choice = null }
        pushRecent()
        val bar = ime.suggestionBar ?: return
        val quiet = ime.wordState.lastCandidates.isEmpty() && ime.lastCommit.stripWord == null && ime.wordState.keptBar == null &&
            ime.expansions.choice == null
        val idle = quiet && ime.config.nextWord && !ime.editorState.privateMode && ime.wordState.tentativeLength == 0 &&
            ime.composer?.hasPendingWord != true
        if (!idle) {
            if (showBeside(bar)) return
            if (barPredictions.isNotEmpty()) {
                barPredictions = emptyList()
                if (quiet) bar.clearSuggestions()
            }
            return
        }
        val prev = previousWordForPrediction(ime.ich.textBeforeCursor(PREDICT_TAIL_CHARS))
        val found = if (prev == null) emptyList() else predictNext(prev).filter { it.word.lowercase() !in ime.residents.blockedSpellings }
        // The pronoun's own capital, which a prediction never went through displayWord for.
        val shown = found.map { AutoCapitalization.forWord(it.word, ime.config.language, it.language.ifEmpty { ime.config.language }) }
        // Against what the bar shows now: it can be cleared behind these predictions' back.
        if (found.isNotEmpty() && bar.showsWords(shown)) {
            barPredictions = found
            return
        }
        barPredictions = found
        if (found.isEmpty()) {
            bar.clearSuggestions()
            return
        }
        ime.wordState.candidateLanguages = found.filter { it.language.isNotEmpty() }
            .associate { it.word.lowercase() to it.language }
        bar.setSuggestions(shown.map { barSuggestion(it) })
        DecodeTrace.log { "  predict next prev=$prev n=${found.size} first=${found.first().word}" }
    }

    /**
     * The strip's first alternatives beside the next words, after a commit: up after every commit
     * with an alternative, the strip kept the next words off the bar. False, and the bar left as
     * it is, when [showsBeside] refuses, so the strip stays.
     */
    private fun showBeside(bar: SuggestionBarView): Boolean {
        val stripWord = ime.lastCommit.stripWord ?: return false
        if (ime.wordState.lastCandidates.isNotEmpty() || ime.wordState.keptBar != null || ime.expansions.choice != null) return false
        if (ime.editorState.privateMode || ime.wordState.tentativeLength > 0 || ime.composer?.hasPendingWord == true) return false
        val zones = ime.lastCommit.besideWords(ime.config.nextWordBesideCount)
        val prev = previousWordForPrediction(ime.ich.textBeforeCursor(PREDICT_TAIL_CHARS))
        val found = if (prev == null || zones.isEmpty()) {
            emptyList()
        } else {
            predictNext(prev).filter { p ->
                p.word.lowercase() !in ime.residents.blockedSpellings && zones.none { it.equals(p.word, ignoreCase = true) }
            }
        }
        if (!showsBeside(true, ime.config.nextWord, ime.config.nextWordBeside, ime.config.recentWords, zones.size, found.isNotEmpty())) {
            // A swap can leave a word nothing follows: the strip comes back, picked word chosen.
            if (besideShown) {
                besideShown = false
                barPredictions = emptyList()
                bar.showCorrection(stripOptions.map { barSuggestion(it) }, stripOptions.indexOf(stripWord).coerceAtLeast(0))
            }
            return false
        }
        val shown = found.map { AutoCapitalization.forWord(it.word, ime.config.language, it.language.ifEmpty { ime.config.language }) }
        barPredictions = found
        besideShown = true
        ime.wordState.candidateLanguages = found.filter { it.language.isNotEmpty() }
            .associate { it.word.lowercase() to it.language }
        bar.showCorrectionBeside(zones.map { barSuggestion(it) }, shown.map { barSuggestion(it) })
        DecodeTrace.log {
            "  predict next beside=${zones.joinToString("|")} prev=$prev n=${found.size} first=${found.first().word}"
        }
        return true
    }

    /**
     * Both resident languages, strongest first. A learned pair only counts where the field
     * allows learning at all, so an app that asks not to be learned from never sees one.
     */
    private fun predictNext(prev: String): List<WordPredictor.NextWord> {
        val personal = !ime.editorState.teachesNothing
        val two = mergeNextWords(
            ime.residents.predictor?.nextWords(prev, KineticaConstants.TOP_K, personal).orEmpty(),
            ime.residents.secondaryPredictor?.nextWords(prev, KineticaConstants.TOP_K, personal).orEmpty(),
            KineticaConstants.TOP_K,
        )
        val third = ime.residents.extraPredictor ?: return two
        return mergeNextWords(two, third.nextWords(prev, KineticaConstants.TOP_K, personal), KineticaConstants.TOP_K)
    }

    /** Current candidates -> suggestion bar, with personal-weight badges. */
    fun pushSuggestions() {
        // The all-tap literal rides along as a last zone, the one-tap way to commit an
        // out-of-dictionary word verbatim (as commitWordInternal does in correction mode). A
        // pick goes through onSuggestionPicked, which bypasses finalizePendingWord, so
        // autocorrect never touches it.
        // The literal is rebuilt from folded key codes, so it would offer "matador" for a
        // typed "matadór". When the two agree up to folding, what the user typed wins.
        val typed = when {
            ime.composer?.hasSwipeToken() == true -> ""
            ime.wordState.tentativeLength > 0 &&
                AccentFolder.fold(ime.wordState.tentativeWord.lowercase()) == ime.wordState.lastLiteral -> ime.wordState.tentativeWord
            else -> ime.displayWord(ime.wordState.lastLiteral)
        }
        val literal = literalZone(
            typed,
            autocorrects = ime.config.autocorrectConfidence != null,
            leavesAccentsOff = ime.residents.predictor?.leavesAccentsOff(typed) == true,
            blocked = typed.lowercase() in ime.residents.blockedSpellings,
        )
        val decoded = ime.wordState.lastCandidates.map { ime.displayWord(it.word) }
        ime.suggestionBar?.setSuggestions(
            suggestionZoneWords(notBlocked(midWordWords(decoded, ime.wordState.midWordHistory, ime.wordState.midWordOffer), ime.residents.blockedSpellings), literal)
                .map { barSuggestion(it) },
        )
        updateBarWordPending()
    }

    fun barSuggestion(display: String): SuggestionBarView.Suggestion {
        val count = ime.countsFor(ime.languageOf(display))[display.lowercase()] ?: 0
        return SuggestionBarView.Suggestion(
            display,
            tier = KineticaConstants.personalTier(count),
            count = count,
        )
    }

    /**
     * True while the bar shows expansion targets: they are not dictionary words, so the weight
     * slide and the block have nothing to act on there.
     */
    fun expansionsOnBar(): Boolean =
        ime.expansions.choice?.let { ime.suggestionBar?.showsWords(it.shown) == true } == true

    /**
     * The weight slide travelled past the bottom of its scale: block [word].
     *
     * - One row per enabled language: a word held by two dictionaries otherwise came back
     *   from the other. The table stays per language so blocking a
     *   junk English name keeps a real Italian word spelled the same.
     * - The user_words row goes too, or it would restore the weight on an unblock.
     * - Gated on [EditorState.teachesNothing] like every learning path: a word written to the
     *   database from a password field is a disclosure.
     */
    fun onSuggestionBlocked(word: String, source: BarAdjust.BlockSource) {
        if (ime.editorState.teachesNothing || word.isEmpty()) return
        if (expansionsOnBar()) {
            DecodeTrace.log { "  weight refused word=$word src=expansion" }
            return
        }
        val lower = word.lowercase()
        val langs = (ime.config.enabledLanguages + ime.config.language).distinct()
        val now = System.currentTimeMillis()
        // Drop it from the live count maps now, so the badge and any personal boost stop
        // before the trie is rebuilt. Only resident languages have a live map.
        for (lang in langs) ime.countsFor(lang).remove(lower)
        ime.residents.blockedSpellings = ime.residents.blockedSpellings + lower
        ime.wordState.lastCandidates = ime.wordState.lastCandidates.filterNot { it.word.lowercase() == lower }
        DecodeTrace.log { "  block word=$lower langs=${langs.joinToString(",")}" }
        ime.views.vibrateForKeyPress()
        ime.dbExecutor.execute {
            try {
                val db = KineticaDb.get(ime)
                for (lang in langs) {
                    db.blockedWords().block(lower, lang, now)
                    db.userWords().delete(lower, lang)
                }
            } catch (e: RuntimeException) {
                Log.w(KineticaIME.TAG, "could not block $lower", e)
                return@execute
            }
            // Queued behind the write on the same executor: a reload that overtakes
            // its write rebuilds the trie from stale rows and the word comes back.
            ime.mainHandler.post { ime.loadDictionaryAsync() }
        }
        if (ime.config.deleteOnBlock && deleteBlockedWord(word, source)) return
        pushSuggestions()
    }

    /**
     * Takes a just-blocked [word] out of the text when it is the word at the cursor: the word
     * just committed, held on its chip or the newest recent column, or the word in progress,
     * held on a live zone. The bar then offers the gesture's other readings. Anything else is
     * blocked and left where it is. True when the text was changed.
     */
    private fun deleteBlockedWord(word: String, source: BarAdjust.BlockSource): Boolean {
        val after = ime.ich.textAfterCursor(1)
        val committed = ime.lastCommit.retypeWord
        val read = if (committed == null) "" else ime.ich.textBeforeCursor(committed.length + KineticaIME.COMMIT_TAIL_CHARS) ?: ""
        val committedSpan = if (committed == null) -1 else commitSpan(read, committed, KineticaIME.COMMIT_TAIL_CHARS)
        val tailIsSpace = committed != null && committedSpan >= committed.length &&
            read.takeLast(committedSpan - committed.length).all { it == ' ' }
        val what = blockDelete(
            source = source,
            word = word,
            tentativeWord = ime.wordState.tentativeWord.takeIf { ime.wordState.tentativeLength > 0 },
            otherPending = (ime.wordState.tentativeLength == 0 && ime.composer?.hasPendingWord == true) ||
                ime.wordState.midWordOffer != null || ime.wordState.keptBar != null,
            selected = ime.selectionLength() > 0,
            letterAfter = after != null && after.isNotEmpty() && after[0].isLetter(),
            committed = committed,
            committedSpan = committedSpan,
            tailIsSpace = tailIsSpace,
        )
        DecodeTrace.log { "  block delete word=$word src=${source.name.lowercase()} -> ${what.name.lowercase()}" }
        when (what) {
            BlockDelete.NONE -> return false
            BlockDelete.COMMIT -> {
                ime.autospace.cancel()
                val offer = notBlocked(ime.lastCommit.offerAfterRetype(committed!!, KineticaConstants.TOP_K), ime.residents.blockedSpellings)
                val languages = ime.lastCommit.languages
                ime.learner.unlearnLastPair()
                ime.forgetCommitMarks()
                ime.ich.deleteBeforeCursor(committedSpan)
                ime.expectAfter(committedSpan, 0)
                recentWords.dropNewest()
                ime.commit.abandonWord()
                ime.autospace.forget()
                ime.updateAutoShift()
                if (offer.isNotEmpty() && !ime.editorState.privateMode) showRetypeOffer(offer, languages) else refreshPredictions()
            }
            BlockDelete.TENTATIVE -> {
                // Cancelled first: a stale timeout left armed would clear the offer below.
                ime.autospace.cancel()
                val offer = notBlocked(ime.wordState.lastCandidates.map { ime.displayWord(it.word) }, ime.residents.blockedSpellings).distinct()
                val languages = ime.wordState.candidateLanguages
                val span = ime.wordState.tentativeLength
                ime.ich.deleteBeforeCursor(span)
                ime.expectAfter(span, 0)
                ime.commit.abandonWord()
                ime.autospace.forget()
                ime.updateAutoShift()
                if (offer.isNotEmpty() && !ime.editorState.privateMode) showRetypeOffer(offer, languages) else refreshPredictions()
            }
        }
        return true
    }

    /** Long-press weight adjustment from the bar: signed, scaled by config. */
    fun onSuggestionReinforced(word: String, delta: Int) {
        if (ime.editorState.teachesNothing || delta == 0) return
        if (expansionsOnBar()) {
            DecodeTrace.log { "  weight refused word=$word src=expansion" }
            return
        }
        // A recent word learns in the language it was decoded in, as its swap does.
        val lang = recentWords.languageOf(word) ?: ime.languageOf(word)
        DecodeTrace.log { "  weight slide word=$word delta=$delta lang=$lang" }
        ime.learner.learnWord(word, delta, lang)
        ime.views.vibrateForKeyPress()
        // Redraw so the adjusted badge appears under the finger, on the columns too.
        pushSuggestions()
        pushRecent()
    }

    /**
     * The rejected gesture's other candidates, left on the bar as a kept bar: a pick
     * inserts at the cursor once the editor is proved unchanged, and is learned into the
     * language it came from. Any gesture or tap replaces it, as a kept bar always has.
     */
    fun showRetypeOffer(words: List<String>, languages: Map<String, String>) {
        val tail = ime.ich.textBeforeCursor(CommitPath.KEPT_BAR_TAIL_CHARS)?.toString() ?: return
        ime.wordState.keptBar = KeptBar("", tail)
        ime.wordState.candidateLanguages = languages
        ime.suggestionBar?.setSuggestions(words.map { barSuggestion(it) })
        updateBarWordPending()
        DecodeTrace.log { "  retype offer n=${words.size} first=${words.first()}" }
    }

    fun clearCorrection() {
        val stripUp = ime.lastCommit.stripWord != null
        ime.lastCommit.clear()
        besideShown = false
        if (stripUp) ime.suggestionBar?.clearCorrection()
    }

    companion object {
        /** Commits the recent-words bar remembers: one more than it shows, for the strip's. */
        const val RECENT_WORDS_KEPT = 3

        /** Recent-word columns on the bar; two leave three fifths of it to the live words. */
        const val RECENT_BAR_COLUMNS = 2

        /** Editor text read to find the recent words again: three long words and their gaps. */
        const val RECENT_TAIL_CHARS = 96

        /** Editor text read to find the word a prediction follows: one long word and its space. */
        const val PREDICT_TAIL_CHARS = 48
    }
}
