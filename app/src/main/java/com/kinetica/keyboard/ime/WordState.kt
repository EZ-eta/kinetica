package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.keys.ShiftState

/**
 * The word in progress and what the bar offers for it, shared by the service and the
 * collaborators that read it. Main thread only.
 *
 * Commit-only text model: the word is [tentativeLength] committed characters before the cursor,
 * replaced in one batch edit as its decode changes.
 */
internal class WordState {

    // Word-in-progress bookkeeping (main thread only).
    var tentativeLength = 0
    var tentativeWord = ""
    var wordShift = ShiftState.State.NONE
    var lastCandidates: List<WordCandidate> = emptyList()

    // The candidate the merge cleared to lead, the only one that may autocorrect a tapped
    // word. Not the list head: when the active language decoded nothing the head is a word
    // the merge refused.
    var lastTentative: WordCandidate? = null
    var lastLiteral = ""

    // Language of each candidate currently on offer, keyed by its display form
    // (lowercased). The bar and the commit path work in strings, so this is how
    // a picked or committed word finds the dictionary it came from.
    var candidateLanguages: Map<String, String> = emptyMap()

    // Same map, snapshotted at commit time: the correction strip outlives
    // lastCandidates, and a correction pick is a real commit that must learn
    // into the right language too.
    var correctionLanguages: Map<String, String> = emptyMap()

    // Set when the latest decode of a swipe-bearing word returned no candidates:
    // the visible tentative is then a stale earlier partial decode that must not
    // be autospaced or learned.
    var swipeDecodeEmpty = false

    // True while the composer holds a word reloaded from the editor that has received no
    // token since. A reload is not a finished word: the user parked the cursor in it or a
    // delete laid it bare. WordComposer.seed decodes, and without this flag that decode
    // armed the autospace timer like a thumb's and put a second space in.
    var seededWithoutTokens = false

    // The word reloadWordUnderCursor seeded back from the editor, if any. Read
    // once at commit to keep a re-commit of unchanged text from being learned
    // twice; see learnsOnCommit.
    var reloadedWord: String? = null

    // The word the cursor was parked inside when the bar was filled for it. It is not a word
    // in progress, so a letter typed there is an ordinary insertion. A pick re-reads both
    // halves before it rewrites anything.
    var midWordOffer: WordAround? = null
    var midWordHistory: CommitHistory.Record? = null

    // Set while the bar shows candidates of a buffer the stale timeout already closed:
    // the earlier decode still on screen, and what the editor held when the buffer closed, so
    // a pick can prove nothing moved since. Null otherwise.
    var keptBar: KeptBar? = null

    // The lesson of the commit reopened to keep typing that word; see Learner.takeBack.
    var reopenedLesson: CommitLesson? = null

    // The commit a reload took out of the composer context, put back if the cursor leaves it.
    var reopenedContext: String? = null
}

/** The bar of a buffer the stale timeout closed: the decode still on screen, and the text then. */
internal class KeptBar(val staleWord: String, val tailAtClose: String)
