package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spacebar's cursor slide ticks once per step, as the backspace slide ticks once per unit, and
 * only where it moves the field's cursor. The haptic call needs a live service, so the guard reads
 * the source ([ImeSources]); a fail-first check here needs `--rerun-tasks`.
 */
class CursorSlideTickSourceTest {

    @Test
    fun eachCursorStepTicksUnderTheVibrationSetting() {
        val src = ImeSources.declaring("override fun onCursorMove(").text
        val start = src.indexOf("override fun onCursorMove(")
        val body = src.substring(start, src.indexOf("\n        }\n", start))
        val search = body.indexOf("if (emojiSearch.active) return")
        val tick = body.indexOf("views.vibrateForKeyPress()")
        assertTrue("a cursor step does not tick", tick >= 0)
        assertTrue("a step under the emoji search ticks", search in 0 until tick)
        val backspace = ImeSources.declaring("fun stage(units: Int, granularity: DeleteSpan.Granularity)").text
        assertTrue("the backspace slide no longer ticks", backspace.contains("ime.views.vibrateForKeyPress()"))
    }
}
