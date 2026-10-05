package com.kinetica.keyboard.layout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * No code turns a character into a letter code by subtracting `'a'`.
 *
 * That arithmetic assumes a-z: on a Cyrillic board `й - 'a'` is 984, and the key burst indexed
 * an array of 63 with it, crashing Russian, Hebrew and Arabic on the first touch. A view's touch
 * path has no JVM reach, so the guard is a source scan like `SteppedSliderSourceTest`. A letter's
 * code comes from its alphabet (`Key.letterCode`, `Alphabet.codeOf`); a chord from
 * `Key.chordChar`, the character itself.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class LatinArithmeticSourceTest {

    private fun mainSources(): List<Path> {
        val root = listOf(Paths.get("src/main/java"), Paths.get("app/src/main/java")).firstOrNull { Files.isDirectory(it) }
        assumeTrue("main sources not found", root != null)
        return Files.walk(root!!).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
    }

    private val arithmetic = Regex("""-\s*'a'|\.minus\(\s*'a'\s*\)""")

    @Test
    fun noLetterCodeIsCountedFromA() {
        val hits = ArrayList<String>()
        for (p in mainSources()) {
            val lines = Files.readAllLines(p)
            for ((i, line) in lines.withIndex()) {
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) continue
                if (arithmetic.containsMatchIn(line)) hits.add("${p.fileName}:${i + 1}: ${line.trim()}")
            }
        }
        assertEquals("letter codes counted from 'a':\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }
}
