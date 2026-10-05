package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The timing sliders' step tables, read off the shipped XML, against the clamps in code.
 *
 * Three sources say what a slider can reach: the table in `arrays.xml`, the clamp in
 * `KeyboardConfig`, and the default in both `keyboard_prefs.xml` and `Prefs`. A table whose
 * floor is below its clamp shows a value the keyboard silently raises; one whose top is
 * below it loses the top users already have (#2 asked for lower floors, not lower ceilings).
 *
 * Read off disk like [PreferenceTreeTest], so a fail-first check here needs `--rerun-tasks`.
 */
class TimingStepsTest {

    private class Slider(val key: String, val array: String, val min: Int, val max: Int, val default: Int)

    private val sliders = listOf(
        Slider(
            Prefs.AUTOSPACE_DELAY_MS, "timing_steps_autospace",
            Prefs.AUTOSPACE_DELAY_MIN_MS, Prefs.AUTOSPACE_DELAY_MAX_MS, Prefs.DEFAULT_AUTOSPACE_DELAY_MS,
        ),
        Slider(
            Prefs.AUTOSPACE_TAP_DELAY_MS, "timing_steps_autospace",
            Prefs.AUTOSPACE_DELAY_MIN_MS, Prefs.AUTOSPACE_DELAY_MAX_MS, Prefs.DEFAULT_AUTOSPACE_TAP_DELAY_MS,
        ),
        Slider(
            Prefs.AUTOSPACE_RETRACT_MS, "timing_steps_retract",
            Prefs.AUTOSPACE_RETRACT_MIN_MS, Prefs.AUTOSPACE_RETRACT_MAX_MS,
            Prefs.DEFAULT_AUTOSPACE_RETRACT_MULTIPLE * Prefs.DEFAULT_AUTOSPACE_TAP_DELAY_MS,
        ),
        Slider(
            Prefs.LONG_PRESS_MS, "timing_steps_long_press",
            Prefs.LONG_PRESS_MIN_MS, Prefs.LONG_PRESS_MAX_MS, Prefs.DEFAULT_LONG_PRESS_MS,
        ),
    )

    private fun read(rel: String): String {
        val direct = Paths.get("src/main/res/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun steps(arrays: String, name: String): List<Int> {
        val block = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(arrays)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>\s*(\d+)\s*</item>""").findAll(block).map { it.groupValues[1].toInt() }.toList()
    }

    /** The XML row for [key]: which table it moves over and what it defaults to. */
    private fun row(prefs: String, key: String): Pair<String, Int> {
        val row = Regex("""<com\.kinetica\.keyboard\.settings\.SteppedSliderPreference[^>]*android:key="$key"[^>]*/>""")
            .find(prefs)?.value
        requireNotNull(row) { "$key is not a stepped slider" }
        val array = Regex("""app:sliderSteps="@array/(\w+)"""").find(row)!!.groupValues[1]
        val default = Regex("""android:defaultValue="(\d+)"""").find(row)!!.groupValues[1].toInt()
        return array to default
    }

    @Test
    fun everyTableSpansExactlyItsClampAndHoldsItsDefault() {
        val arrays = read("values/arrays.xml")
        for (s in sliders) {
            val t = steps(arrays, s.array)
            assertTrue("${s.array} is empty", t.isNotEmpty())
            assertTrue("${s.array} must increase strictly", t.zipWithNext().all { (a, b) -> b > a })
            assertEquals("${s.key} floor", s.min, t.first())
            assertEquals("${s.key} ceiling", s.max, t.last())
            assertTrue("${s.key} default ${s.default} must be a step", s.default in t)
        }
    }

    @Test
    fun eachSliderMovesOverItsOwnTableWithTheCodeDefault() {
        val prefs = read("xml/keyboard_prefs.xml")
        for (s in sliders) {
            val (array, default) = row(prefs, s.key)
            assertEquals(s.key, s.array, array)
            assertEquals("${s.key} XML default against Prefs", s.default, default)
        }
    }

    @Test
    fun theLowEndIsFinerThanTheHighEnd() {
        // The point of a table: a thumb can land on 10, 15 and 20.
        val t = steps(read("values/arrays.xml"), "timing_steps_autospace")
        assertEquals(listOf(10, 15, 20), t.take(3))
        assertTrue(t[1] - t[0] < t.last() - t[t.size - 2])
    }

    @Test
    fun aValueOffTheTableShowsAtItsNearestStep() {
        val t = intArrayOf(10, 15, 20, 300, 350)
        assertEquals(3, SliderSteps.nearestIndex(t, 310))
        assertEquals(4, SliderSteps.nearestIndex(t, 340))
        assertEquals(0, SliderSteps.nearestIndex(t, 1))
        assertEquals(4, SliderSteps.nearestIndex(t, 5000))
        // A tie goes low.
        assertEquals(0, SliderSteps.nearestIndex(t, 12))
        assertEquals(1, SliderSteps.nearestIndex(t, 13))
    }
}
