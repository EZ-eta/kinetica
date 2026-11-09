package com.kinetica.keyboard.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue

/**
 * The generated emoji assets, read off disk. Gradle does not see them as test inputs, so a
 * fail-first check against them needs `--rerun-tasks`.
 */
object EmojiAssetFiles {

    val LANGS = listOf("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ro", "ru", "he", "ar", "uk")

    fun path(name: String): Path {
        val direct = Paths.get("src/main/assets/emoji/$name")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/emoji/$name")
        assumeTrue("$name not found", Files.exists(p))
        return p
    }

    fun lines(name: String): List<String> = Files.readAllLines(path(name))

    val catalog: EmojiCatalog by lazy { EmojiCatalog.parse(lines("emoji.tsv").asSequence()) }

    private val words = HashMap<String, List<EmojiCatalog.Words>>()

    fun words(lang: String): List<EmojiCatalog.Words> =
        words.getOrPut(lang) { EmojiCatalog.parseWords(lines("kw_$lang.tsv").asSequence()) }

    /** The top [n] for [query] searched in [langs], as strings for readable failures. */
    fun top(query: String, vararg langs: String, n: Int = EmojiSearch.RESULTS): List<String> =
        EmojiSearch.rank(query, catalog.entries, langs.map { words(it) }, limit = n).map { it.ch }
}
