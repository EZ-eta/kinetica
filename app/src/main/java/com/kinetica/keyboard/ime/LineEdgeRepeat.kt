package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.EditorAction

/**
 * Home or End pressed again goes on a line: one arrow past the edge, then the edge again, so the
 * editor's own line model decides, soft-wrapped lines included. Again means the last command was
 * the same edge and the user has not moved the cursor since.
 *
 * Pure, so the rule is testable without an editor. Main thread only.
 */
internal class LineEdgeRepeat(
    /** A user move this soon after the edge went is the edge key's own report: a key event's move is not expected. */
    private val echoMs: Long = SelectionLedger.OWN_REPORT_WINDOW_MS,
) {
    private var last: EditorAction? = null
    private var sentAt = Long.MIN_VALUE

    /** True when [edge] repeats the edge still standing. Records it; any other command (null) clears. */
    fun press(edge: EditorAction?, now: Long): Boolean {
        val again = edge != null && edge == last
        last = edge
        sentAt = now
        return again
    }

    /** The user moved the cursor: the next press starts from wherever it is now. */
    fun onUserMove(now: Long) {
        if (last != null && now - sentAt > echoMs) last = null
    }

    fun clear() {
        last = null
    }

    companion object {
        /** Home or End when [output] names one, else null. */
        fun edgeOf(output: String): EditorAction? =
            EditorAction.of(output)?.takeIf { it == EditorAction.HOME || it == EditorAction.END }

        /**
         * The arrow that steps past [edge] onto the next line: back for Home, ahead for End, turned
         * round in right-to-left text ([before] and [after] the cursor), as the word step is.
         */
        fun stepPast(edge: EditorAction, before: CharSequence, after: CharSequence): EditorAction {
            val inText = if (edge == EditorAction.HOME) -1 else 1
            return if (wordStepDirection(inText, before, after) < 0) EditorAction.ARROW_LEFT else EditorAction.ARROW_RIGHT
        }
    }
}
