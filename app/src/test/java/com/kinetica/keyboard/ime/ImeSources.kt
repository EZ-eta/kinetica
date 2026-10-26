package com.kinetica.keyboard.ime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue

/**
 * Every Kotlin file of the `ime` package, read off disk, for the guards that need a live editor
 * and scan the source instead.
 *
 * The whole package, not one file: code moves between the service and its collaborators, and a
 * guard pinned to one path skipped silently once its code left. A symbol a guard asks for and
 * finds nowhere fails it.
 */
internal object ImeSources {

    class Source(val name: String, val text: String) {
        fun lineOf(offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1
    }

    fun all(): List<Source> {
        val rel = "src/main/java/com/kinetica/keyboard/ime"
        val direct = Paths.get(rel)
        val dir: Path = if (Files.isDirectory(direct)) direct else Paths.get("app/$rel")
        assumeTrue("ime sources not found", Files.isDirectory(dir))
        return Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".kt") }
                .sorted()
                .map { p -> Source(p.fileName.toString(), Files.newBufferedReader(p).use { it.readText() }) }
                .toList()
        }
    }

    /** The one file that declares [declaration]; fails when none or several do. */
    fun declaring(declaration: String): Source {
        val found = all().filter { it.text.contains(declaration) }
        require(found.size == 1) { "$declaration found in ${found.map { it.name }}" }
        return found.single()
    }
}
