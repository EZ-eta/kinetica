package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backspace tap or repeat takes a whole emoji, never half of one (#56).
 *
 * `Backspace.onKey` needs a live editor, so the guard is a source scan; the cut itself is
 * `DeleteSpan.grapheme`, covered by `DeleteSpanTest`. Read off disk ([ImeSources]), so a
 * fail-first check here needs `--rerun-tasks`.
 */
class BackspaceTapSpanSourceTest {

    private fun body(src: String, head: String): String {
        val start = src.indexOf(head)
        assertTrue("$head not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun aTapDeletesTheWholeCharacterBeforeTheCursor() {
        val src = ImeSources.declaring("fun onKey(tap: Boolean").text
        val onKey = body(src, "fun onKey(tap: Boolean")
        assertTrue("onKey's span is not tapSpan", onKey.contains("tapSpan("))
        assertTrue("onKey deletes a fixed count", !onKey.contains("deleteBeforeCursor(1)"))
        val tap = body(src, "private fun tapSpan(")
        assertTrue("tapSpan never asks DeleteSpan.grapheme", tap.contains("DeleteSpan.grapheme("))
        assertTrue("tapSpan dropped the spaces rule", tap.contains("backspaceSpan("))
    }
}
