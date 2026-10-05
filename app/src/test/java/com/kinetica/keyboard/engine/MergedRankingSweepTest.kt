package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The merged-ranking acceptance sweep: both enabled languages' dictionaries, every word
 * decoded through both predictors and ranked into one list.
 *
 * It guards against one asset quietly winning everything. Both directions are swept
 * (it active / es secondary and the reverse) so a bias toward either dictionary fails
 * instead of passing as a lucky asymmetry. Two properties:
 *
 * 1. Merging must not disturb a same-language decode: for every word of the active
 *    language, the merged lead is the word the active predictor alone would lead with.
 *    If cross-dictionary frequency were not comparable, or the promotion rule too
 *    loose, this fails first and at scale.
 * 2. A word only the other language has must lead. A whole-list swap reached 23 of 38
 *    rows offline and 2 of 10 on device, because between two Romance languages "the
 *    active language has no explanation at all" is much rarer than a foreign word.
 *
 * Each case runs clean and sloppy, since a sloppy path is the realistic one and the two
 * can disagree (precision is what broke pass merging; see PassRunSplitTest).
 */
class MergedRankingSweepTest {

    private val direct = Executor { it.run() }
    private val g = TestData.qwertyGeometry()

    private object NoCallbacks : WordComposer.Callbacks {
        override fun onCandidates(
            candidates: List<WordCandidate>,
            tentative: WordCandidate?,
            literal: String,
            generation: Int,
        ) = Unit
    }

    private fun predictor(lang: String): WordPredictor {
        val (dict, bigrams) = if (lang == "it") IT else ES
        return WordPredictor(dict.trie, bigrams, g, dict.forms, language = lang)
    }

    private fun swipeFor(w: String, sloppy: Boolean): InputToken =
        if (sloppy) {
            TestData.sloppySwipe(w, g, 0, 100L * w.length, 0.4f, StreamId.LEFT)
        } else {
            TestData.swipe(w, g, 0, 100L * w.length, StreamId.LEFT)
        }

    private class Row(
        val aloneLead: String?,
        val merged: WordComposer.Merged,
        val activeFit: Float,
        val otherList: List<WordCandidate>,
    )

    private fun row(active: String, other: String, w: String, sloppy: Boolean): Row {
        val a = predictor(active)
        val tokens = listOf(swipeFor(w, sloppy))
        val alone = a.decode(tokens, emptyList())
        val otherList = predictor(other).decode(tokens, emptyList())
        val m = WordComposer(a, direct, direct, NoCallbacks).merge(alone, otherList)
        return Row(
            alone.firstOrNull()?.word,
            m,
            alone.minOfOrNull { it.dtwDistance } ?: Float.MAX_VALUE,
            otherList,
        )
    }

    /** Accent restoration counts as finding the word: "manana" -> "mañana". */
    private fun same(a: String?, b: String) =
        a != null && AccentFolder.fold(a) == AccentFolder.fold(b)

    @Test
    fun mergingNeverDisturbsAnActiveLanguageDecode() {
        val bad = ArrayList<String>()
        var n = 0
        for ((active, other, words) in PAIRINGS) {
            for (w in words) {
                for (sloppy in listOf(false, true)) {
                    n++
                    val r = row(active, other, w, sloppy)
                    if (r.merged.tentative?.word != r.aloneLead) {
                        bad += "$active '$w'${if (sloppy) " sloppy" else ""}: " +
                            "${r.aloneLead} -> ${r.merged.tentative?.word}"
                    }
                }
            }
        }
        assertEquals("a second language changed $bad of $n same-language decodes", 0, bad.size)
    }

    @Test
    fun aWordOnlyTheOtherLanguageHasLeadsUnlessTheActiveOneFitsAsWell() {
        // The full contract of rule 2c, asserted uniformly with no list of
        // exceptions: a word only the other language has must lead, unless the
        // active language explains the same path at least as well, and then it
        // must still be pickable, because a tie is no reason to hide it.
        // Of 76 rows, 59 lead and 17 tie. Every tie is one of two shapes:
        //   - a doubled letter shares its ideal path after consecutive-duplicate
        //     dedup, so interessante/interesante, necessario/necesario,
        //     citta/cita, notte/noté and posso/piso decode at equal d and the
        //     active language keeps its own word (the rese/reese class, across
        //     languages);
        //   - "puedo" on a sloppy path, where Italian "perdo" fits at 0.193
        //     against 0.194, a real competition decided by a thousandth.
        // Accent restoration counts as leading: "manana" -> "mañana".
        val bad = ArrayList<String>()
        var lead = 0
        var n = 0
        for ((active, other, _) in PAIRINGS) {
            val foreign = if (other == "es") ES_ONLY else IT_ONLY
            for (w in foreign) {
                for (sloppy in listOf(false, true)) {
                    n++
                    val r = row(active, other, w, sloppy)
                    val tag = "$active+$other '$w'${if (sloppy) " sloppy" else ""}"
                    if (same(r.merged.tentative?.word, w)) {
                        lead++
                        continue
                    }
                    val fit = r.otherList.filter { same(it.word, w) }
                        .minOfOrNull { it.dtwDistance }
                    when {
                        fit == null -> bad += "$tag: not decodable in $other at all"
                        r.activeFit > fit ->
                            bad += "$tag lost to ${r.merged.tentative?.word} " +
                                "despite fitting better ($fit vs ${r.activeFit})"
                        !r.merged.candidates.any { same(it.word, w) } ->
                            bad += "$tag tied but was not pickable"
                    }
                }
            }
        }
        assertEquals("$bad (of $n rows, $lead led)", 0, bad.size)
        assertTrue("only $lead of $n foreign rows led; 59 were measured", lead >= 55)
    }

    @Test
    fun twoLanguageDecodeLatencyIsBounded() {
        // The other *LatencyIsBounded goldens call one predictor directly, so
        // none covers the configuration that runs when a second language is
        // enabled. Both decodes are on the path to every suggestion and need
        // their own bound: 1.5 ms per word against the standard 100 ms budget.
        // The merge itself is two sorts of at most ten elements plus a trie
        // lookup per foreign candidate.
        val a = predictor("it")
        val o = predictor("es")
        val buffers = listOf(
            listOf(swipeFor("interessante", sloppy = false)),
            listOf(swipeFor("parlare", sloppy = false)),
            listOf(swipeFor("nosotros", sloppy = true)),
        )
        val composer = WordComposer(a, direct, direct, NoCallbacks)
        for (b in buffers) composer.merge(a.decode(b, emptyList()), o.decode(b, emptyList()))
        val t0 = System.nanoTime()
        var n = 0
        repeat(20) {
            for (b in buffers) {
                composer.merge(a.decode(b, emptyList()), o.decode(b, emptyList()))
                n++
            }
        }
        val perWordMs = (System.nanoTime() - t0) / n / 1_000_000.0
        assertTrue("two-language decode took $perWordMs ms", perWordMs < 100.0)
    }

    @Test
    fun aForeignWordMayCrowdTheWindowWithoutLookingLikeAnEmptyDecode() {
        // Rule 2's "did the active language produce anything" test once read
        // the TOP_K-truncated list. On "ciudad" every Italian candidate (best
        // fit d=0.795) scores below all ten Spanish ones, so the window held no
        // Italian word and the rule read that as an empty decode, refusing a
        // Spanish word at d=0.000. Crowded out of the bar is not the same as
        // having nothing to say, so both operands come from the full decode.
        for (w in listOf("ciudad", "verdad")) {
            val r = row("it", "es", w, sloppy = false)
            assertEquals("$w must lead", w, r.merged.tentative?.word)
            assertEquals("foreign-lead", r.merged.reason)
        }
    }

    private companion object {
        // Words present in it_wordlist; the starred dozen are in es_wordlist
        // too, which exercises the shared-word filter at scale.
        val IT_WORDS = listOf(
            "sempre", "quindi", "interessante", "sudare", "sarei", "siete", "lei", "loro",
            "quando", "perche", "grazie", "domani", "lavoro", "casa", "tempo", "bene",
            "sono", "fare", "vedere", "portare", "mangiare", "lavorare", "necessario",
            "parlare", "citta", "uomo", "notte", "piccolo", "voglio", "posso", "dire",
            "strada", "verita", "mondo",
        )

        // In es_wordlist and not in it_wordlist, checked against the assets.
        val ES_ONLY = listOf(
            "ayudarte", "trabajo", "siempre", "mujer", "nosotros", "cuando", "manana",
            "ahora", "ciudad", "mientras", "entonces", "nunca", "tambien", "pueblo",
            "pequeno", "puedo", "hacer", "decir", "verdad",
        )

        // In it_wordlist and not in es_wordlist, for the reverse direction.
        val IT_ONLY = listOf(
            "quindi", "interessante", "sudare", "sarei", "perche", "domani", "lavoro",
            "vedere", "mangiare", "lavorare", "necessario", "parlare", "citta", "uomo",
            "notte", "voglio", "posso", "strada", "verita",
        )

        val ES_WORDS = ES_ONLY + listOf("siete", "casa", "grazie", "tempo", "bene", "dire")

        val PAIRINGS = listOf(
            Triple("it", "es", IT_WORDS),
            Triple("es", "it", ES_WORDS),
        )

        private fun assetPath(name: String): Path {
            val direct = Paths.get("src/main/assets/dictionaries/$name")
            if (Files.exists(direct)) return direct
            return Paths.get("app/src/main/assets/dictionaries/$name")
        }

        private fun load(lang: String): Pair<LoadedDictionary, BigramTable> {
            val w = assetPath("${lang}_wordlist.txt")
            val b = assetPath("${lang}_bigrams.txt")
            assumeTrue("$lang assets not found", Files.exists(w) && Files.exists(b))
            val dict = Files.newBufferedReader(w).use { DictionaryLoader.load(it) }
            val bigrams = Files.newBufferedReader(b).use {
                DictionaryLoader.loadBigrams(it, dict.trie)
            }
            return dict to bigrams
        }

        val IT by lazy { load("it") }
        val ES by lazy { load("es") }
    }
}
