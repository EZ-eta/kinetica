package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.ui.BarAdjust

/**
 * Whether the editor's selection [live] is still the span the backspace slide highlighted
 * ([staged]), so lifting may delete it. A selection moved meanwhile is not deleted: the delete
 * acts on what the editor holds, and that must be what the slide showed. [unreadableTail]
 * characters at the end were a selection the editor would not hand over, so only their count
 * is compared. An editor that reports no selected text is trusted with its own highlight.
 */
internal fun stagedStillSelected(live: String?, staged: String, unreadableTail: Int): Boolean {
    if (live == null) return true
    // Equal heads of equal drops: the lengths agree too.
    return live.dropLast(unreadableTail) == staged.dropLast(unreadableTail)
}

/**
 * A word reloaded under the cursor and not typed on since is committed as written: punctuation
 * after a word the user returned to must not swap it for a prediction (a name read as a word).
 */
internal fun keepsAsWritten(reloadedWord: String?, tentativeWord: String): Boolean =
    reloadedWord != null && reloadedWord == tentativeWord

/**
 * How much of the committed [word] is left before the cursor while backspace eats it: -1 while
 * it is still whole, the letters left while the run before the cursor is a prefix of it, 0 once
 * it is gone, null once the cursor has left it.
 */
internal fun backspaceLeft(before: CharSequence, word: String, maxTrailing: Int): Int? {
    if (commitSpan(before, word, maxTrailing) >= 0) return -1
    val run = trailingLetterRun(before)
    if (run.isEmpty()) return 0
    return if (run.length < word.length && word.startsWith(run, ignoreCase = true)) run.length else null
}

/**
 * How many characters before the cursor the committed [word] and whatever the editor put
 * after it occupy, or -1 when the editor does not hold [word] there.
 *
 * Asked of the editor, since a remembered trailing string drifted and re-casing `be?` gave
 * `bBE`. [maxTrailing] bounds what one mis-tracked commit can delete:
 * a longer run of marks means the word is not where the caller believes, so the answer is a
 * refusal. The word run is walked with [isWordChar], as the recase, retype and reload do.
 * Case is ignored because only the length is used, and auto-capitalization may have written
 * a letter the caller does not carry.
 */
internal fun commitSpan(before: CharSequence, word: String, maxTrailing: Int): Int {
    if (word.isEmpty()) return -1
    var i = before.length
    while (i > 0 && before.length - i < maxTrailing && !isWordChar(before[i - 1])) i--
    val start = i - word.length
    if (start < 0) return -1
    if (!before.subSequence(start, i).toString().equals(word, ignoreCase = true)) return -1
    return before.length - start
}

/**
 * How much text a pick from a kept bar replaces, or -1 to refuse.
 *
 * The stale timeout closed the buffer and left its candidates up, so the pick arrives after
 * the word state was cleared and no remembered length can be trusted.
 * [tailAtClose] is what the editor held before the cursor when the buffer closed: unless it
 * still holds that, something was typed or deleted since. [staleWord] is the earlier
 * decode still on screen, empty when the gesture never produced one.
 */
internal fun keptBarPickSpan(tailNow: CharSequence, tailAtClose: CharSequence, staleWord: String): Int {
    if (tailNow.toString() != tailAtClose.toString()) return -1
    if (!tailAtClose.endsWith(staleWord)) return -1
    return staleWord.length
}

/**
 * How much text a retype deletes: the word in progress, else the word just committed with
 * whatever the keyboard put after it, else the letters the cursor is parked at the end of.
 *
 * Autospace commits fast, so a wrong word is usually committed by the time it is noticed; its
 * trailing space goes too, or the retyped word would land one space along. [wordUnderCursor]
 * covers an undecodable buffer: the stale timeout's `abandonWord` clears [tentativeLength]
 * and [CommitMemory] while the letters stay on screen. It is the read `reloadWordUnderCursor`
 * trusts, with the same guard, so a cursor parked mid-word does nothing.
 */
internal fun retypeSpan(
    tentativeLength: Int,
    commitSpan: Int,
    wordUnderCursor: String,
): Int = when {
    tentativeLength > 0 -> tentativeLength
    commitSpan >= 0 -> commitSpan
    else -> wordUnderCursor.length
}

/**
 * Which of [retypeSpan]'s three cases answered, for the trace.
 *
 * Takes [commitSpan], not the committed word: a word the editor no longer holds did not
 * answer, the run under the cursor did, and the trace has to say so.
 */
internal fun retypeSource(tentativeLength: Int, commitSpan: Int): String = when {
    tentativeLength > 0 -> "tentative"
    commitSpan >= 0 -> "commit"
    else -> "cursor"
}

/**
 * True when a slide or an unlearn takes a word that was merged into the trie (at or above the
 * merge floor) down: its trie frequency still holds the old count until the next load.
 */
internal fun userDictDemoted(countBefore: Int, countAfter: Int): Boolean =
    countBefore >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT && countAfter < countBefore

/**
 * Whether a commit may rewrite the letters on screen. Not in a URL or email field: an address is
 * no dictionary word. Not with a [selection] in the field: an app's completion sits selected after
 * the cursor (Firefox's `eb|ay.com/`), and a rewrite deletes it (#52).
 */
internal fun commitMayRewrite(addressField: Boolean, selection: Int): Boolean =
    !addressField && selection == 0

/**
 * Whether a delimiter may still rewrite a word's letters: tapped only, letters to read, not a
 * private field, [commitMayRewrite]. Shared by the commit and by the bar's bold, so the two never
 * disagree.
 */
internal fun tapCommitMayCorrect(
    hasSwipe: Boolean,
    literal: String,
    privateMode: Boolean,
    addressField: Boolean,
    selection: Int,
): Boolean = !hasSwipe && literal.isNotEmpty() && !privateMode && commitMayRewrite(addressField, selection)

/**
 * The word a space would write now, before its capital. A swipe writes its lead ([swipeLead]; with
 * none, the [tentative] already on screen). Tapped letters are written as typed unless
 * [autocorrect] gives a target, the gate [CommitPath.finalizePendingWord] applies.
 */
internal fun spaceCommitWord(
    tentative: String,
    swipeLead: String?,
    hasSwipe: Boolean,
    literal: String,
    privateMode: Boolean,
    addressField: Boolean,
    selection: Int,
    asWritten: Boolean,
    autocorrect: () -> String?,
): String {
    if (hasSwipe) return swipeLead ?: tentative
    if (!asWritten && tapCommitMayCorrect(hasSwipe, literal, privateMode, addressField, selection)) {
        autocorrect()?.let { return it }
    }
    return tentative
}

/**
 * Whether the trie must be rebuilt because a word's personal count has just made it
 * mergeable, judged from the count before and after one [Learner.learnWord] call.
 *
 * The count map only supplies a ranking multiplier and cannot boost a candidate the trie
 * never produced, so a word the dictionary lacks stays undecodable until a load merges it.
 * True on the crossing only, so a word the reload cannot admit
 * (blocked, or past USER_DICT_LIMIT) costs one parse, not one per commit.
 */
internal fun userDictNeedsReload(
    countBefore: Int,
    countAfter: Int,
    trieHasWord: Boolean,
): Boolean = !trieHasWord &&
    countBefore < KineticaConstants.PERSONAL_MERGE_MIN_COUNT &&
    countAfter >= KineticaConstants.PERSONAL_MERGE_MIN_COUNT

/**
 * Whether a parsed dictionary must wait before it is installed: a word is on screen or in the
 * composer. The install replaces the composer, and the word loses its tokens and its space.
 */
internal fun holdsDictionaryInstall(tentativeLength: Int, composerPending: Boolean): Boolean =
    tentativeLength > 0 || composerPending

/**
 * A dictionary install kept until the word in progress ends.
 *
 * The reload's guard looks at fire time only, and the parse is long enough for a word to start
 * under it. Only the newest load is kept: an older one would install stale rows.
 */
internal class HeldInstall {
    private var held: (() -> Unit)? = null

    val isHeld: Boolean get() = held != null

    /** Runs [install] now, or keeps it while [wordPending]. True when kept. */
    fun offer(wordPending: Boolean, install: () -> Unit): Boolean {
        if (wordPending) {
            held = install
            return true
        }
        held = null
        install()
        return false
    }

    /** Runs the kept install unless [wordPending]. True when it ran. */
    fun release(wordPending: Boolean): Boolean {
        val install = held ?: return false
        if (wordPending) return false
        held = null
        install()
        return true
    }
}

/**
 * The count changes a dictionary load has not read. Each load opens a journal when its row read is
 * queued; its install replays the journal over the rows, so a word learned mid-load keeps its count.
 *
 * Main thread only. A load held under a word can wait seconds, and a learn in that time was lost.
 */
internal class LearnJournal {
    class Learn(val lang: String, val word: String, val amount: Int)

    private var next = 0
    private val open = LinkedHashMap<Int, MutableList<Learn>>()
    // The newest load whose rows are live, -1 before the first install.
    private var installed = -1

    /** A new load's journal; its token goes to [take]. */
    fun open(): Int {
        val load = next++
        open[load] = ArrayList()
        return load
    }

    /** One count change, kept by every load still open. */
    fun note(lang: String, word: String, amount: Int) {
        if (open.isEmpty()) return
        val learn = Learn(lang, word, amount)
        for (learns in open.values) learns += learn
    }

    /**
     * [load]'s changes, in order, or null once it is closed: a newer load superseded it. Closes it
     * and every older load: an older install after a newer one would put back older rows.
     */
    fun take(load: Int): List<Learn>? {
        val learns = open[load] ?: return null
        open.keys.removeAll { it <= load }
        return learns
    }

    /**
     * Called when [load]'s parse is posted, before it installs or is held. False when a newer load
     * already did: [load] read older rows, and its trie and blocked set are older too. Otherwise
     * closes every older load, whose post may still be on its way.
     */
    fun supersede(load: Int): Boolean {
        if (load !in open) return false
        open.keys.removeAll { it < load }
        return true
    }

    /** [load]'s rows are now the live counts. */
    fun installed(load: Int) {
        installed = maxOf(installed, load)
    }

    /** The token a learn keeps: a load opened before it has a smaller one. */
    fun mark(): Int = next

    /**
     * A learn's stored row read [gap] above its live count: a row past the load limit, never read.
     * Every load opened before the learn ([mark]) read its rows before the write and gets the gap;
     * one opened after reads the row itself. True when the live counts also lack it: no load
     * opened after the learn has installed.
     */
    fun correct(mark: Int, lang: String, word: String, gap: Int): Boolean {
        val learn = Learn(lang, word, gap)
        for ((load, learns) in open) if (load < mark) learns += learn
        return installed < mark
    }
}

/**
 * How far a learn's live count [after] reads below its stored row [stored], read back behind the
 * write. A row past the load limit is never read, so a word at 1 counted 0 to 1 live while the row
 * went to 2: no merge, no reload, until the next load. Never negative: a live count above the row
 * is left alone, and a failed read ([stored] null) corrects nothing.
 */
internal fun learnedCountGap(after: Int, stored: Int?): Int =
    if (stored == null) 0 else (stored - after).coerceAtLeast(0)

/**
 * Replays the [learns] filed in [lang] over [counts], clamped at zero each step as the live map and
 * the database are. Returns the words that crossed the merge floor and [trieHas] lacks: the rows
 * missed them, so the new trie did too.
 */
internal fun replayLearns(
    counts: MutableMap<String, Int>,
    lang: String,
    learns: List<LearnJournal.Learn>,
    trieHas: (String) -> Boolean,
): List<String> {
    val before = HashMap<String, Int>()
    for (l in learns) {
        // An unlearn of a word with no row leaves no row, as the live map's computeIfPresent does.
        if (l.lang != lang || (l.amount <= 0 && l.word !in counts)) continue
        val now = counts[l.word] ?: 0
        before.putIfAbsent(l.word, now)
        counts[l.word] = (now + l.amount).coerceAtLeast(0)
    }
    return before.filter { (w, b) -> userDictNeedsReload(b, counts[w] ?: 0, trieHas(w)) }.keys.toList()
}

/**
 * [candidates] with the word a retype just rejected moved to the end.
 *
 * Demoted, not dropped: of 78 retype presses, 12 handed back the word just rejected, but the
 * wanted word was among the alternates in only 5 of them, and a retype aimed at a
 * space still needs the word. Moving it last promotes the runner-up and hides nothing.
 *
 * Returns [candidates] itself when there is nothing to do, so the caller can test identity.
 */
internal fun demoteRejected(
    candidates: List<WordCandidate>,
    rejected: String?,
): List<WordCandidate> {
    if (rejected == null || candidates.size < 2) return candidates
    val i = candidates.indexOfFirst { it.word.equals(rejected, ignoreCase = true) }
    if (i < 0) return candidates
    return candidates.toMutableList().apply { add(removeAt(i)) }
}

/**
 * Whether committing [word] adds a unit of personal weight, given the word [reloadedFrom]
 * seeded back from the editor (null when typed from nothing).
 *
 * Deleting only the space after "hello" reloads it, and the next delimiter commits it again:
 * one authored word would earn two units, the self-reinforcing drift the merge floor and the
 * fade are tuned against. An edit still learns: "hell" reloaded and committed as "hello"
 * counts. Pure because the learn path has no JVM reach.
 */
internal fun learnsOnCommit(word: String, reloadedFrom: String?): Boolean {
    if (reloadedFrom == null) return true
    return !word.equals(reloadedFrom, ignoreCase = true)
}

/**
 * Whether committing [committed] takes back the commit [reopened] names: the word an automatic
 * space followed, the space then taken back to keep typing. Typed on into another word, that
 * commit was not the word (`Fake` of `FakeCompanyName`); committed again unchanged, it stays
 * learned once, as [learnsOnCommit] keeps it.
 */
internal fun takesBackReopened(reopened: String?, committed: String): Boolean =
    reopened != null && committed.isNotEmpty() && !keepsReopened(reopened, committed)

/** Whether committing [committed] is the reopened commit [reopened] again, case aside: learned once. */
internal fun keepsReopened(reopened: String?, committed: String): Boolean =
    reopened != null && committed.equals(reopened, ignoreCase = true)

/**
 * How much a backspace takes back to undo an autocorrect: the corrected word, plus the one space
 * or mark typed after it when [typedAfter] is 1. -1 when the text before the cursor no longer
 * ends that way, or more than one character followed.
 */
internal fun autocorrectUndoSpan(before: CharSequence, corrected: String, typedAfter: Int): Int {
    if (typedAfter !in 0..1 || corrected.isEmpty()) return -1
    val span = corrected.length + typedAfter
    if (before.length < span) return -1
    val tail = before.subSequence(before.length - span, before.length)
    if (!tail.startsWith(corrected)) return -1
    if (typedAfter == 1 && tail.last().isLetterOrDigit()) return -1
    return span
}

/** What blocking a word does to the text, with deleting on block on. */
internal enum class BlockDelete { COMMIT, TENTATIVE, NONE }

/**
 * Whether a blocked [word] is the text at the cursor, and which: the last commit, held on its
 * chip or the newest recent column, nothing in progress, nothing selected, no letter after the
 * cursor, and the editor still ending in it ([committedSpan] proved, as a retype proves it) with
 * nothing after it but spaces ([tailIsSpace]: a period or a newline typed after it is the
 * user's); or the word in progress, held on a live zone. Every other block leaves the text
 * alone: a wrong delete costs more than a wrong block.
 */
internal fun blockDelete(
    source: BarAdjust.BlockSource,
    word: String,
    tentativeWord: String?,
    otherPending: Boolean,
    selected: Boolean,
    letterAfter: Boolean,
    committed: String?,
    committedSpan: Int,
    tailIsSpace: Boolean = true,
): BlockDelete = when {
    selected -> BlockDelete.NONE
    source == BarAdjust.BlockSource.LIVE && tentativeWord != null && tentativeWord.equals(word, ignoreCase = true) ->
        BlockDelete.TENTATIVE
    (source == BarAdjust.BlockSource.CHIP || source == BarAdjust.BlockSource.NEWEST_RECENT) &&
        tentativeWord == null && !otherPending && !letterAfter &&
        committed != null && committed.equals(word, ignoreCase = true) && committedSpan > 0 && tailIsSpace -> BlockDelete.COMMIT
    else -> BlockDelete.NONE
}

/** [words] without the blocked spellings: a block means never offered, wherever the word was kept. */
internal fun notBlocked(words: List<String>, blocked: Set<String>): List<String> =
    if (blocked.isEmpty()) words else words.filter { it.lowercase() !in blocked }
