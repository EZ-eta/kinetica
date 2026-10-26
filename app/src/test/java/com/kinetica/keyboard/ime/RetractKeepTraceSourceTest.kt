package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tapped word's space that a letter does not take back leaves one line naming why.
 *
 * The reason is `retractRefusal`, covered by `TapAutospaceTest`; the line needs a live editor, so
 * the guard is a source scan. Read off disk ([ImeSources]), so a fail-first check here needs
 * `--rerun-tasks`.
 */
class RetractKeepTraceSourceTest {

    @Test
    fun aKeptSpaceIsTracedWithItsGate() {
        val src = ImeSources.declaring("fun retractBeforeLetter(").text
        val start = src.indexOf("fun retractBeforeLetter(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue("retractBeforeLetter asks no reason", body.contains("val why = retractRefusal("))
        assertTrue(
            "a kept space leaves no line",
            body.contains("autospace keep why=\$why elapsed=\$elapsed window="),
        )
        assertTrue(
            "the line is not scoped to a tapped word's space",
            body.contains("} else if (fromTaps && inserted && ime.wordState.tentativeLength == 0) {"),
        )
    }
}
