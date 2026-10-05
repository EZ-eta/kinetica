package com.kinetica.keyboard.ime

/**
 * Tells the editor's cursor reports for the keyboard's own edits from the user's moves.
 *
 * Each own edit records the position it leaves, and a report is the keyboard's only when it
 * lands on one. A count of reports to swallow went wrong because an edit that leaves the cursor
 * in place (a same-length rewrite, as most two-thumb merges are) gets no report: the count crept
 * up and swallowed eight user moves in 99 commits. A position never reported is never matched.
 *
 * Pure, so the rule is testable without an editor. Main thread only.
 */
class SelectionLedger(
    /** An unmatched report this soon after an own edit is still taken as the edit's. */
    private val mismatchWindowMs: Long = OWN_REPORT_WINDOW_MS,
    /** How long an edit whose position cannot be known (paste, undo) claims the next report. */
    private val wildcardMs: Long = WILDCARD_MS,
    /** How long any expectation lives. */
    private val lifeMs: Long = LIFE_MS,
) {
    enum class Verdict { OWN, MISMATCH, USER }

    private class Entry(val start: Int, val end: Int, val at: Long, val any: Boolean)

    private val entries = ArrayDeque<Entry>()
    private var lastEditAt = Long.MIN_VALUE

    /** Expectations still open. */
    val pending: Int get() = entries.size

    /** The position the last expected own edit leaves, or null with none open. */
    fun lastExpected(): Pair<Int, Int>? = entries.lastOrNull { !it.any }?.let { it.start to it.end }

    /** An own edit leaves the selection at [start]..[end]. */
    fun expect(start: Int, end: Int, now: Long) {
        add(Entry(start, end, now, any = false))
    }

    /** An own edit whose resulting position cannot be known. */
    fun expectAny(now: Long) {
        add(Entry(-1, -1, now, any = true))
    }

    private fun add(e: Entry) {
        entries.addLast(e)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        lastEditAt = e.at
    }

    /** Whether the report of [start]..[end] at [now] is the keyboard's own; it consumes what it matches. */
    fun judge(start: Int, end: Int, now: Long): Verdict {
        entries.removeAll { now - it.at > (if (it.any) wildcardMs else lifeMs) }
        val i = entries.indexOfFirst { it.any || (it.start == start && it.end == end) }
        if (i >= 0) {
            repeat(i + 1) { entries.removeFirst() }
            return Verdict.OWN
        }
        val recent = lastEditAt != Long.MIN_VALUE && now - lastEditAt <= mismatchWindowMs
        entries.clear()
        return if (recent) Verdict.MISMATCH else Verdict.USER
    }

    fun clear() {
        entries.clear()
        lastEditAt = Long.MIN_VALUE
    }

    companion object {
        // An own edit's report arrives within a frame or two; a user's tap on the text cannot
        // follow a keystroke this closely.
        const val OWN_REPORT_WINDOW_MS = 150L
        const val WILDCARD_MS = 500L
        const val LIFE_MS = 3_000L
        const val MAX_ENTRIES = 16
    }
}
