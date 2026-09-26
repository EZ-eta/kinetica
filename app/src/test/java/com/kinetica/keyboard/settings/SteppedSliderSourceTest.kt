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
 * The androidx `Preference` base constructor calls `onGetDefaultValue` while it is still
 * running, before this subclass's `init` block has set `steps`, so reading `steps` there is
 * an NPE that crashes every settings open (1.1.1f). A preference cannot be inflated in a JVM
 * test - it needs a Context and real resources - which is why that shipped, so the guard is a
 * source scan, the same shape as `PreferenceTreeTest` and `InitOrderTest`.
 *
 * Read straight off disk: **`--rerun-tasks` is what makes a fail-first check here run.**
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
