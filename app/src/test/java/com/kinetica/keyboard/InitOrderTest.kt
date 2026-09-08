package com.kinetica.keyboard

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * No class-level property may be declared below its own `init` block.
 *
 * Kotlin runs property initializers and `init` blocks in DECLARATION order, so a property
 * written below `init` is still null while `init` runs. Anything `init` calls that reads it
 * throws out of the constructor, and for a view built on the key-handling path that means
 * the keyboard dies rather than the feature failing: `EmojiPickerView.glyphPaint` sat eleven
 * lines too low and the emoji panel took the whole IME down with it. KNOWN_ISSUES item 64.
 *
 * Reads the source off disk, the same way [com.kinetica.keyboard.ui.EmojiDataTest] and
 * [com.kinetica.keyboard.settings.PreferenceTreeTest] read the assets, so **`--rerun-tasks`
 * is what makes a fail-first check here actually run.**
 *
 * **This is a heuristic and the escape is deliberate.** A property never touched from `init`
 * is safe anywhere, so a future case may trip this honestly. The fix is to move the property
 * above `init` or to make it a function, which has no ordering problem at all. Relaxing the
 * test instead needs a stated reason, because what it prevents is a dead keyboard.
 */
class InitOrderTest {

    private fun sourceRoot(): Path {
        val direct = Paths.get("src/main/java")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/java")
    }

    /** Class-member `init`, at exactly one indent level. */
    private val initBlock = Regex("""^ {4}init\s*\{""")

    /**
     * A class-member property whose value is computed AT CONSTRUCTION, which is what carries
     * the ordering. Two shapes qualify: an `=` initializer and a `by` delegate, since the
     * delegate object is itself built by an initializer.
     *
     * A type may not contain brackets here on purpose. That is what excludes
     * `val x: Boolean get() = ...`, a computed property with no backing field, which is safe
     * anywhere and was the first thing this caught.
     */
    private val initialisedProperty =
        Regex("""^ {4}(?:private |internal |protected |public )?(?:@\w+\s+)?(?:val|var)\s+(\w+)\s*(?::\s*[\w<>?.,\s\[\]]+?)?\s*(?:=|\bby\b)\s""")

    @Test
    fun noPropertyIsDeclaredBelowItsOwnInitBlock() {
        val root = sourceRoot()
        assumeTrue("source tree not found", Files.exists(root))
        val files = Files.walk(root).use { s ->
            s.filter { it.toString().endsWith(".kt") }.collect(Collectors.toList())
        }
        assumeTrue("no sources found", files.isNotEmpty())

        val offenders = ArrayList<String>()
        for (f in files) {
            val lines = Files.readAllLines(f)
            val init = lines.indexOfFirst { initBlock.containsMatchIn(it) }
            if (init < 0) continue
            for (i in init until lines.size) {
                val m = initialisedProperty.find(lines[i]) ?: continue
                offenders += "${f.fileName}:${i + 1} ${m.groupValues[1]} is below init at line ${init + 1}"
            }
        }
        assertTrue(
            "a property below its own init block is null while init runs:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theCheckActuallyLooksAtSomething() {
        // A regex that matched nothing would pass the test above forever.
        val root = sourceRoot()
        assumeTrue("source tree not found", Files.exists(root))
        val withInit = Files.walk(root).use { s ->
            s.filter { it.toString().endsWith(".kt") }
                .filter { f -> Files.readAllLines(f).any { initBlock.containsMatchIn(it) } }
                .count()
        }
        assertTrue("no class with an init block was found, so the walk is broken", withInit > 0)
    }
}
