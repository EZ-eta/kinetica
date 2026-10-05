package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `SteppedSliderPreference.onGetDefaultValue` must not read the `steps` field.
 *
 * The androidx `Preference` base constructor calls `onGetDefaultValue` before this subclass's
 * `init` block has set `steps`, so reading it there throws an NPE on every settings open. A
 * preference cannot be inflated in a JVM test (it needs a Context and real resources), so the
 * guard is a source scan, like `PreferenceTreeTest` and `InitOrderTest`.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class SteppedSliderSourceTest {

    private fun source(): String {
        val direct = Paths.get("src/main/java/com/kinetica/keyboard/settings/SteppedSliderPreference.kt")
        val p: Path = if (Files.exists(direct)) {
            direct
        } else {
            Paths.get("app/src/main/java/com/kinetica/keyboard/settings/SteppedSliderPreference.kt")
        }
        assumeTrue("SteppedSliderPreference.kt not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun onGetDefaultValueNeverReadsTheStepTable() {
        val src = source()
        // The single-expression body: everything between the signature and the end of its line.
        val line = Regex("""fun onGetDefaultValue\([^)]*\)[^\n]*""").find(src)?.value
        requireNotNull(line) { "onGetDefaultValue not found" }
        assertFalse(
            "onGetDefaultValue reads steps, which is null when the base constructor calls it",
            line.contains("steps"),
        )
    }
}
