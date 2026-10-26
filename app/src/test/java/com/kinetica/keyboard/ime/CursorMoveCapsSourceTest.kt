package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cursor moved by the user re-decides the shift from the text before the new position.
 *
 * The shift is otherwise decided only after the keyboard's own edits, so a period typed and the
 * cursor moved back into the sentence capitalized the next letter. `onUpdateSelection` needs a
 * live editor, so the guard is a source scan, like `SteppedSliderSourceTest`. The decision
 * itself is `startsNewSentence`, covered by `SentenceCapsTest`.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class CursorMoveCapsSourceTest {

    @Test
    fun aUserCursorMoveRedecidesTheShift() {
        val src = ImeSources.declaring("override fun onUpdateSelection(").text
        val start = src.indexOf("override fun onUpdateSelection(")
        val end = src.indexOf("\n    }\n", start)
        val body = src.substring(start, end)
        // Past the early return for the keyboard's own reports: only a user move re-decides.
        val userPath = body.substring(body.indexOf("return\n"))
        assertTrue(
            "onUpdateSelection's user path never calls updateAutoShift",
            userPath.contains("updateAutoShift()"),
        )
    }
}
