package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Add button sits above the list on both shortcut screens, so a long list needs no scroll to
 * add one. An Activity needs a Context, so the order is read off the source.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class AddButtonPlacementTest {

    private fun source(file: String): String {
        val rel = "src/main/java/com/kinetica/keyboard/settings/$file"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$file not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun assertAddAboveList(file: String, addString: String) {
        val src = source(file)
        val add = src.indexOf("R.string.$addString")
        val list = src.indexOf("root.addView(listContainer)")
        require(add >= 0 && list >= 0) { "$file: markers not found" }
        assertTrue("$file adds its Add button below the list", add < list)
    }

    @Test
    fun chordsAddAboveTheList() = assertAddAboveList("ChordSettingsActivity.kt", "chord_add")

    @Test
    fun expansionsAddAboveTheList() = assertAddAboveList("ExpansionSettingsActivity.kt", "expansion_add")
}
