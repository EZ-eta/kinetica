package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retraction asks the engine whether the other thumb's swipe began the word.
 *
 * The rule is `retractsAutospace`, covered by `TapAutospaceTest`; the engine's answer by
 * `GestureEngineTest`. The call between them needs a live editor, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class RetractSwipeHeldSourceTest {

    @Test
    fun theRetractionPassesTheHeldSwipe() {
        val src = ImeSources.declaring("fun retractBeforeLetter(").text
        val start = src.indexOf("fun retractBeforeLetter(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue(
            "retractBeforeLetter never asks swipeHeldSince",
            body.contains("val held = ime.engine.swipeHeldSince(tStart)") && body.contains("swipeStartedFirst = held"),
        )
    }
}
