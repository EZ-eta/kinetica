package com.kinetica.keyboard.ime

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.BigramTable
import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.TestData
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.keys.AutoCapitalization
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A tapped apostrophe joins the word in hand when the two can still become a word of a language that
 * writes contractions as one word (#55): `I` `'` `d` is `I'd`. Italian and French elide, so `l'altro`
 * stays two words there whatever the user has learned, and in Mix and Follow too.
 */
class ApostropheJoinTest {

    private val g = TestData.qwertyGeometry()
    private val latin = Alphabet.LATIN

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    /** [lang]'s bundled list with [learned] user rows (word to count) merged as a load merges them. */
    private fun lexicon(lang: String, learned: List<Pair<String, Int>> = emptyList(), alphabet: Alphabet = latin): WordPredictor {
        val p = assetPath("${lang}_wordlist.txt")
        assumeTrue("${lang}_wordlist asset not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use {
            DictionaryLoader.load(it, extraWords = DictionaryLoader.userWordsForMerge(learned), alphabet = alphabet)
        }
        return WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms, language = lang, learnedOnly = dict.learned)
    }

    /** The enabled languages are the residents here, so Italian or French among them is the eliding switch. */
    private fun joins(word: String, vararg residents: Pair<String, WordPredictor>): Boolean =
        apostropheJoins(word, residents.toList(), latin, elidingEnabled(residents.map { it.first }))

    /** Taps spelling [word], each [s] kw off its key, alternating sides; the apostrophe as itself. */
    private fun tapped(word: String, s: Float): List<InputToken> =
        word.mapIndexed { i, c ->
            val t = 180L * i
            if (c == '\'') {
                TapToken.apostrophe(StreamId.RIGHT, latin.apostrophe, t)
            } else {
                val code = latin.codeOf(c)
                val sign = if (i % 2 == 0) 1f else -1f
                val x = g.centerX(code) + sign * s
                val y = g.centerY(code) - sign * s / 2f
                TapToken(StreamId.LEFT, g.keyAt(x, y), x, y, false, t, t + 60)
            }
        }

    private fun literal(tokens: List<InputToken>): String =
        tokens.sortedBy { it.tStart }.joinToString("") { latin.charOf((it as TapToken).code).toString() }

    @Test
    fun aContractionsHeadJoinsOnEnglish() {
        val en = "en" to lexicon("en")
        for (w in listOf("I", "i", "don", "Don", "we", "he", "she", "it", "can")) assertTrue(w, joins(w, en))
        // No word goes on from these, so the apostrophe ends them as before.
        for (w in listOf("rock", "dogs", "l", "dell", "un", "d")) assertFalse(w, joins(w, en))
        // Nothing in hand is a quote opening, not a contraction.
        assertFalse(joins("", en))
    }

    @Test
    fun italianAndFrenchElisionNeverJoin() {
        // Elisions a learned Italian trie holds, merged as a load merges them.
        val it = "it" to lexicon("it", listOf("l'ora" to 2, "c'è" to 2, "com'è" to 19, "l'altro" to 3))
        val fr = "fr" to lexicon("fr")
        val en = "en" to lexicon("en")
        // The data would join them: learned rows, the Italian junk rows, French `l'ai`, `m'en`, `t'ai`.
        for (h in listOf("l'", "c'", "com'", "e'", "n'")) assertTrue(h, it.second.isLivePrefix(h))
        for (h in listOf("l'", "m'", "t'", "n'")) assertTrue(h, fr.second.isLivePrefix(h))
        // The lists alone hold none of the common heads.
        for (h in listOf("l'", "dell'", "un'", "d'")) {
            assertFalse(h, lexicon("it").isLivePrefix(h))
            assertFalse(h, en.second.isLivePrefix(h))
        }
        val heads = listOf("l", "L", "dell", "un", "d", "n", "e", "c", "com", "nell", "all", "m", "t", "s")
        for (h in heads) {
            assertFalse("$h alone", joins(h, it))
            assertFalse("$h with English second", joins(h, it, en))
            assertFalse("$h with English first", joins(h, en, it))
            assertFalse("$h in French", joins(h, fr))
            assertFalse("$h French and English", joins(h, fr, en))
        }
        // English resident beside Italian still joins its own contractions.
        assertTrue(joins("I", it, en))
        assertTrue(joins("don", it, en))
    }

    @Test
    fun aRefusedElisionLeavesTheTailAWordOfItsOwn() {
        // `l` then `'` breaks the word, so `altro` is typed as a new one and decodes alone.
        val it = lexicon("it")
        assertFalse(joins("l", "it" to it, "en" to lexicon("en")))
        val tokens = "altro".mapIndexed { i, c -> TestData.tap(c, g, 1_000L + 150L * i, StreamId.LEFT) }
        assertEquals("altro", it.decode(tokens, listOf("l")).first().word)
    }

    @Test
    fun withItalianOnLearnedElisionsInEnglishDoNotJoin() {
        // Italian elisions filed to the English rows made `l'` live there, and `l'altro` joined.
        val learned = listOf("l'ora" to 2, "l'irb" to 2, "l'altro" to 2, "nell'altro" to 2)
        val en = "en" to lexicon("en", learned)
        val it = "it" to lexicon("it")
        for (h in listOf("l", "nell")) {
            assertTrue(h, en.second.isLivePrefix("$h'"))
            assertFalse(h, joins(h, en, it))
            assertFalse(h, joins(h, it, en))
        }
        // The English list's own contractions still join.
        for (h in listOf("I", "don", "we")) assertTrue(h, joins(h, en, it))
        // French on alone is enough.
        assertFalse(joins("l", en, "fr" to lexicon("fr")))
        // With no eliding language enabled, a learned row joins as before.
        assertTrue(joins("l", en))
    }

    @Test
    fun aSwipedHeadBeforeTheApostropheIsAnElision() {
        val it = elisionHeadsFor(listOf("it", "en"))
        // A swipe cannot show `ll`: the elision outranks the commoner word.
        assertEquals("nell", elisionHead(listOf("nel", "nell", "bel"), it))
        // None among the first three: the longest head one of them begins with.
        assertEquals("quest", elisionHead(listOf("quel", "questo", "queste"), it))
        assertEquals("dell", elisionHead(listOf("dello", "della", "del"), it))
        assertEquals("c", elisionHead(listOf("cl", "con", "col"), it))
        // Past the third candidate nothing is read.
        assertEquals(null, elisionHead(listOf("bene", "beh", "ben", "nell"), it))
        // No eliding language: the lead stands.
        assertEquals(null, elisionHead(listOf("nel", "nell"), elisionHeadsFor(listOf("en"))))
        assertEquals("qu", elisionHead(listOf("que", "quoi"), elisionHeadsFor(listOf("fr"))))
    }

    @Test
    fun aListPrefixIsOneTheUserDidNotOnlyLearn() {
        val en = lexicon("en", listOf("l'ora" to 2, "y'all" to 2, "we'dz" to 2))
        assertTrue(en.holdsListPrefix("i'"))
        assertTrue(en.holdsListPrefix("don'"))
        assertFalse(en.holdsListPrefix("l'"))
        assertFalse(en.holdsListPrefix("qq'"))
        assertFalse(en.holdsListPrefix(""))
        // Learned only: `y'all` is no row of the list.
        assertTrue(en.isLivePrefix("y'"))
        assertFalse(en.holdsListPrefix("y'"))
        // A learned word under a listed prefix leaves the prefix listed.
        assertTrue(en.holdsListPrefix("we'd"))
    }

    @Test
    fun aLearnedContractionJoins() {
        val plain = lexicon("en")
        assertFalse(joins("y", "en" to plain))
        // Learned past the merge floor, `y'all` is a word of the user's English.
        val learned = lexicon("en", listOf("y'all" to 2))
        assertTrue(joins("y", "en" to learned))
        assertTrue(joins("Y", "en" to learned))
        for (s in listOf(0f, 0.25f, 0.45f)) {
            val tokens = tapped("y'all", s)
            assertEquals("y'all", literal(tokens))
            assertEquals("at $s kw", "y'all", learned.decode(tokens, listOf("hey")).first().word)
        }
        // One commit is not yet a learned word.
        assertFalse(joins("y", "en" to lexicon("en", listOf("y'all" to 1))))
    }

    @Test
    fun ukrainianJoinsInsideAWord() {
        val uk = lexicon("uk", alphabet = Alphabet.UKRAINIAN)
        assertTrue(apostropheJoins("п", listOf("uk" to uk), Alphabet.UKRAINIAN, elidingOn = false))
        assertTrue(apostropheJoins("ім", listOf("uk" to uk), Alphabet.UKRAINIAN, elidingOn = false))
        // A lexicon in another script than the board's has no say.
        assertFalse(apostropheJoins("п", listOf("uk" to uk), latin, elidingOn = false))
        // Russian writes no apostrophe: nothing joins on its board.
        assertFalse(apostropheJoins("п", listOf("uk" to uk), Alphabet.CYRILLIC, elidingOn = false))
        // Italian enabled beside it: the Ukrainian list still holds its own words.
        assertTrue(apostropheJoins("п", listOf("uk" to uk), Alphabet.UKRAINIAN, elidingOn = true))
    }

    @Test
    fun aTappedIdCommitsAndLearnsAsOneWord() {
        val en = lexicon("en")
        assertTrue(joins("I", "en" to en))
        val tokens = tapped("i'd", 0.25f)
        assertEquals("i'd", literal(tokens))
        val lead = en.decode(tokens, listOf("said")).first()
        assertEquals("i'd", lead.word)
        // A word, so a space keeps it; written with its capital; learned whole.
        assertEquals(null, en.tapAutocorrect("i'd", lead, 0.85f))
        assertEquals("I'd", AutoCapitalization.forWord("i'd", "en"))
        assertEquals("I'd", AutoCapitalization.forWord("i'd", "it", "en"))
        assertTrue(learnable("i'd"))
        assertTrue(learnsOnCommit("I'd", null))
        // A trailing apostrophe is no word to learn.
        assertFalse(learnable("i'"))
    }

    private fun body(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        assertTrue("$declaration not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun bothApostropheKeysJoinThroughOnePath() {
        // The service has no JVM reach: the tap and the hold must both ask the join before ending the word.
        val src = ImeSources.declaring("private fun joinApostrophe(").text
        val punct = body(src, "private fun onPunctuation(")
        val action = punct.indexOf("commands.performIfAction(text)")
        val join = punct.indexOf("if (joinApostrophe(text, key)) return")
        val eat = punct.indexOf("autospace.eatBefore(text)")
        assertTrue(punct, action in 0 until join && join < eat)
        assertTrue(src.contains("KeyType.CHAR -> onPunctuation(key.output, key)"))
        // Every join question carries the enabled languages' eliding switch.
        val ask = body(src, "internal fun joinsAtApostrophe(")
        assertTrue(ask, ask.contains("elidingEnabled(config.enabledLanguages + config.language)"))
        assertTrue(src.contains("if (!composeAccentedLetter(text) && !joinApostrophe(text, key)) finalizeThenCommitText(text)"))
        assertTrue(body(src, "override fun onKeyPressFeedback(").contains("keyDownAt[key.id] = SystemClock.uptimeMillis()"))
        val fn = body(src, "private fun joinApostrophe(")
        val order = listOf(
            "autospace.retractBeforeApostrophe(t)",
            "comp.hasSwipeToken()",
            "joinsAtApostrophe(word)",
            "rememberWordBeforeGesture()",
            "commitTracked(\"'\")",
            "wordState.tentativeWord += \"'\"",
            "comp.onToken(TapToken.apostrophe(",
        ).map { step -> fn.indexOf(step).also { assertTrue("missing: $step", it >= 0) } }
        assertEquals(order.sorted(), order)
        // The retract asks the join, not the letter's prefix.
        val auto = ImeSources.declaring("fun retractBeforeApostrophe(").text
        assertTrue(auto.contains("retractBefore(tStart, -1L) { fusedBase, _ -> ime.joinsAtApostrophe(fusedBase) }"))
    }
}
