package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A settings row follows a value the keyboard writes while the screen is open: a language change
 * sets the letter arrangement beside it, drag-resize the height. A stale row touched afterwards
 * writes the old value back. A preference cannot be inflated in a JVM test, so the wiring is a
 * source scan, like [SteppedSliderSourceTest]. Read off disk: a fail-first check needs `--rerun-tasks`.
 */
class RowSyncSourceTest {

    private fun source(name: String): String {
        val direct = Paths.get("src/main/java/com/kinetica/keyboard/settings/$name")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/java/com/kinetica/keyboard/settings/$name")
        assumeTrue("$name not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun theFragmentResyncsOnResumeAndListensUntilPause() {
        val src = source("KeyboardPrefsFragment.kt")
        val resume = body(src, "override fun onResume()")
        assertTrue("onResume must resync the screen", resume.contains("RowSync.applyAll("))
        assertTrue("onResume must listen", resume.contains("registerOnSharedPreferenceChangeListener(rowSync)"))
        assertTrue("onPause must stop listening", body(src, "override fun onPause()").contains("unregisterOnSharedPreferenceChangeListener(rowSync)"))
    }

    @Test
    fun everyKindOfRowIsSet() {
        val src = source("RowSync.kt")
        for (kind in listOf(
            "TwoStatePreference", "ListPreference", "SeekBarPreference", "EditTextPreference",
            "MultiSelectListPreference", "SteppedSliderPreference", "ColorHexPreference",
        )) {
            assertTrue("$kind rows are not followed", src.contains("is $kind ->"))
        }
    }
}
