package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a shift press the retraction asks the learned spellings, within the shift bound.
 *
 * The rules are `learnedCamelPrefix` and `shiftRetractMs`, covered by `TapAutospaceTest`; the call
 * needs a live editor, so the guard is a source scan. Read off disk ([ImeSources]), so a fail-first
 * check here needs `--rerun-tasks`.
 */
class RetractShiftSourceTest {

    @Test
    fun aShiftPressAsksTheLearnedSpellings() {
        val src = ImeSources.declaring("fun retractBeforeLetter(").text
        val start = src.indexOf("fun retractBeforeLetter(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue("no shift bound", body.contains("if (shifted) shiftRetractMs(ime.config.autospaceRetractMs)"))
        assertTrue(
            "the shift does not ask the learned spellings",
            body.contains("learnedCamelPrefix(fusedBase, ime.shift.apply(letter), learnedDisplays())"),
        )
        assertTrue("the gate is not told of the shift", body.contains("shifted = shifted,"))
        assertTrue(
            "the learned spellings skip a resident language",
            src.contains("ime.residents.languages(ime.config.language).flatMap { lang -> ime.casesFor(lang)"),
        )
    }
}
