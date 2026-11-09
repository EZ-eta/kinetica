package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Long learned words by length: one stroke, pieces of at most 12 letters, and taps.
 *
 * Each word is learned twice, so it merges into the trie as a typed word does. A word the trie
 * dropped reads `trie=0`, apart from one it holds but the decode missed. The taps control decodes
 * the same words unlearned, for the cost of the fallbacks walking a long buffer.
 * `KINETICA_LONG=1` to run; it prints `LONG` lines.
 */
class LongWordProbe {

    private class Lang(val code: String, val g: KeyboardGeometry)

    private val langs = listOf(
        Lang("de", TestData.qwertzGeometry()),
        Lang("it", TestData.qwertyGeometry()),
        Lang("pl", TestData.qwertyGeometry()),
    )

    private val modes = listOf("stroke0", "stroke25", "stroke45", "split", "taps", "tapsUnlearned")

    @Test
    fun reportLongWordsByLength() {
        assumeTrue("set KINETICA_LONG=1 to run", System.getenv("KINETICA_LONG") != null)
        // found, top1, words, worst ns, per length and mode
        val rows = sortedMapOf<Int, MutableMap<String, LongArray>>()
        var inTrie = 0
        var total = 0
        for (lang in langs) {
            val path = assetPath("${lang.code}_wordlist.txt")
            assumeTrue("${lang.code} assets not found", Files.exists(path))
            val list = readList(path)
            val words = probeWords(list).toMutableList()
            if (lang.code == "it") words.add("supercalifragilistichespiralidoso")
            val learned = words.map { it to 2 }
            val taught = Files.newBufferedReader(path).use {
                DictionaryLoader.load(it, DictionaryLoader.userWordsForMerge(learned))
            }
            val p = WordPredictor(
                taught.trie, BigramTable.EMPTY, lang.g, taught.forms, learned.toMap(),
                language = lang.code, learnedOnly = taught.learned,
            )
            val plain = Files.newBufferedReader(path).use { DictionaryLoader.load(it) }
            val bare = WordPredictor(plain.trie, BigramTable.EMPTY, lang.g, plain.forms, emptyMap(), language = lang.code)
            // Warm the JIT, so the first word does not carry the compile.
            repeat(3) { p.decode(tokens(words.first(), "stroke25", lang.g), emptyList()) }
            for (w in words) {
                total++
                if (taught.trie.contains(w)) inTrie++
                for (mode in modes) {
                    val predictor = if (mode == "tapsUnlearned") bare else p
                    val t = tokens(w, mode.removeSuffix("Unlearned"), lang.g)
                    val t0 = System.nanoTime()
                    val out = predictor.decode(t, emptyList())
                    val ns = System.nanoTime() - t0
                    val read = out.map { AccentFolder.fold(it.word.lowercase()) }
                    val c = rows.getOrPut(w.length) { HashMap() }.getOrPut(mode) { LongArray(4) }
                    if (w in read) c[0]++
                    if (read.firstOrNull() == w) c[1]++
                    c[2]++
                    c[3] = maxOf(c[3], ns)
                }
                println("LONG word ${lang.code} ${w.length} $w trie=${if (taught.trie.contains(w)) 1 else 0}")
            }
        }
        println("LONG caps maxWordLen=${KineticaConstants.MAX_WORD_LEN} words=$total inTrie=$inTrie")
        println("LONG len " + modes.joinToString(" ") { "$it(found/top1/n ms)" })
        for ((len, byMode) in rows) {
            println("LONG $len " + modes.joinToString(" ") { m ->
                val c = byMode[m] ?: LongArray(4)
                "${c[0]}/${c[1]}/${c[2]} ${"%.1f".format(c[3] / 1e6)}"
            })
        }
    }

    /** The tokens of one way of writing [w]: a stroke at an overshoot, pieces, or one tap a letter. */
    private fun tokens(w: String, mode: String, g: KeyboardGeometry): List<InputToken> = when (mode) {
        "stroke0" -> listOf(TestData.sloppySwipe(w, g, 0, 60L * w.length, 0f))
        "stroke25" -> listOf(TestData.sloppySwipe(w, g, 0, 60L * w.length, 0.25f))
        "stroke45" -> listOf(TestData.sloppySwipe(w, g, 0, 60L * w.length, 0.45f))
        "split" -> {
            // Pieces as even as 12 letters allow, the thumbs taking turns, one after the other.
            val k = (w.length + 11) / 12
            val out = ArrayList<InputToken>()
            var from = 0
            var t = 0L
            for (i in 0 until k) {
                val to = from + (w.length - from) / (k - i)
                val piece = w.substring(from, to)
                val stream = if (i % 2 == 0) StreamId.LEFT else StreamId.RIGHT
                out.add(TestData.sloppySwipe(piece, g, t, 60L * piece.length, 0.25f, stream))
                t += 60L * piece.length + 150
                from = to
            }
            out
        }
        else -> w.mapIndexed { i, ch ->
            val code = g.alphabet.codeOf(ch)
            val stream = if (g.centerX(code) < g.centerX(g.alphabet.codeOf('g'))) StreamId.LEFT else StreamId.RIGHT
            TestData.tap(ch, g, 150L * i, stream)
        }
    }

    /**
     * Three words a length from 16 to 40. To 20, a list's most frequent words of that length;
     * past it, the most frequent pair of the list's words that adds up to it, the way compounds
     * are made, since no bundled word is longer than 20.
     */
    private fun probeWords(list: List<String>): List<String> {
        val plain = list.filter { it.all { c -> c in 'a'..'z' } }
        val out = ArrayList<String>()
        for (len in 16..20) out.addAll(plain.filter { it.length == len }.take(PER_LENGTH))
        // The most frequent words of each length from 8 to 20, so every sum up to 40 has a pair.
        val parts = (8..20).flatMap { n -> plain.filter { it.length == n }.take(20) }
        for (len in 21..40) {
            var n = 0
            loop@ for (a in parts) {
                for (b in parts) {
                    if (a != b && a.length + b.length == len && (a + b) !in out) {
                        out.add(a + b)
                        if (++n == PER_LENGTH) break@loop
                        break
                    }
                }
            }
        }
        return out
    }

    /** The list's spellings, folded as the trie keys them, most frequent first. */
    private fun readList(path: Path): List<String> = Files.readAllLines(path).mapNotNull { line ->
        val w = line.substringBefore('\t')
        if (w.isEmpty() || !line.contains('\t')) null else AccentFolder.fold(w.lowercase())
    }.distinct()

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private companion object {
        const val PER_LENGTH = 3
    }
}
