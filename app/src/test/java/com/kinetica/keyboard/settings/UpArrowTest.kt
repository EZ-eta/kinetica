package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every screen that shows the back arrow handles it. The arrow alone does nothing: Text
 * expansions showed it without the handler, and only the system back left the screen.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class UpArrowTest {

    private fun sources(): List<Path> {
        val rel = "src/main/java/com/kinetica/keyboard"
        val direct = Paths.get(rel)
        val root: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$rel not found", Files.exists(root))
        return Files.walk(root).use { s -> s.filter { it.toString().endsWith("Activity.kt") }.toList() }
    }

    @Test
    fun everyScreenWithTheArrowHandlesIt() {
        val shown = sources().filter { p ->
            Files.newBufferedReader(p).use { it.readText() }.contains("setDisplayHomeAsUpEnabled(true)")
        }
        assertTrue("no screen shows the arrow", shown.size >= 9)
        val unhandled = shown.filterNot { p ->
            Files.newBufferedReader(p).use { it.readText() }.contains("override fun onSupportNavigateUp()")
        }
        assertTrue("arrow without a handler: ${unhandled.map { it.fileName }}", unhandled.isEmpty())
    }

}
