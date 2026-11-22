/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The apostrophe key as a place a stroke can reach: the board keeps it beside the letters, a
 * stroke passes over it or ends on it, and a capture's board line carries it. Strokes are sloppy,
 * on QWERTY as the layout draws it with the key on.
 */
class ApostropheKeyTest {

    private companion object {
        val ELISIONS = listOf("nell'altro", "dell'anno", "quest'anno", "c'era", "l'uomo", "un'amica", "sull'erba", "l'acqua")
    }

    private val g0 = TestData.qwertyHomeRowShifted()
    private val gK = TestData.qwertyWithApostropheKey()
    private val a = gK.alphabet.apostrophe
    private fun code(c: Char) = gK.alphabet.codeOf(c)

    private fun segment(word: String, g: KeyboardGeometry = gK, overshoot: Float = 0.25f): Matcher.Segment =
        Matcher.buildSegment(TestData.sloppySwipe(word, g, 1_000L, 600L, overshoot), g)

    private val predictors = HashMap<String, WordPredictor>()

    private fun predictor(lang: String): WordPredictor = predictors.getOrPut(lang) {
        val direct: Path = Paths.get("src/main/assets/dictionaries/${lang}_wordlist.txt")
        val p = if (Files.exists(direct)) direct else Paths.get("app/src/main/assets/dictionaries/${lang}_wordlist.txt")
        assumeTrue("$lang wordlist not found", Files.exists(p))
        val dict = Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
        WordPredictor(dict.trie, BigramTable.EMPTY, g0, dict.forms, language = lang)
    }

    /** The lead of one sloppy stroke through [spelling]'s stops, `'` at the key, on board [g]. */
    private fun lead(lang: String, spelling: String, g: KeyboardGeometry, overshoot: Float): String? =
        leadOf(lang, listOf(TestData.sloppySwipe(spelling, g, 1_000L, 700L, overshoot)), g)

    /**
     * The lead of [tokens] on board [g], the language's elision heads given as the keyboard does, and a `'`
     * tapped inside a stroke riding it.
     */
    private fun leadOf(lang: String, tokens: List<com.kinetica.keyboard.engine.models.InputToken>, g: KeyboardGeometry): String? {
        val p = predictor(lang)
        p.geometry = g
        p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf(lang))
        p.ridesApostrophes = true
        try {
            return p.decode(tokens, emptyList()).firstOrNull()?.word
        } finally {
            p.geometry = g0
            p.elisionHeads = emptySet()
            p.ridesApostrophes = false
        }
    }

    /** Points through [spelling]'s stops (`'` at the key), each turn overshot by [overshoot] kw, ten a leg. */
    private fun points(spelling: String, overshoot: Float, pauseAfterKey: Boolean = false): List<Pair<Float, Float>> {
        fun stops(w: String) = w.map { c ->
            val code = if (c == '\'') a else code(c)
            gK.centerX(code) to gK.centerY(code)
        }
        fun vertices(st: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            out.add(st[0])
            for (i in 1 until st.size) {
                val (px, py) = st[i - 1]
                val (cx, cy) = st[i]
                out.add(cx to cy)
                val len = kotlin.math.hypot(cx - px, cy - py)
                if (i < st.size - 1 && len > 1e-3f) out.add(cx + (cx - px) / len * overshoot to cy + (cy - py) / len * overshoot)
            }
            return out
        }
        fun sample(vs: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            for (k in 0 until vs.size - 1) for (j in 0 until 10) {
                val f = j / 10f
                out.add(vs[k].first + f * (vs[k + 1].first - vs[k].first) to vs[k].second + f * (vs[k + 1].second - vs[k].second))
            }
            out.add(vs.last())
            return out
        }
        if (!pauseAfterKey) return sample(vertices(stops(spelling)))
        val k = spelling.indexOf('\'')
        val lead = sample(vertices(stops(spelling.substring(0, k + 2))))
        return lead + List(25) { lead.last() } + sample(vertices(stops(spelling.substring(k + 1)))).drop(1)
    }

    /** A gesture engine on [board], by default the keyed one, collecting what it lifts into [into]. */
    private fun engine(into: MutableList<com.kinetica.keyboard.engine.models.InputToken>, board: KeyboardGeometry = gK) = GestureEngine(object : GestureEngine.Listener {
        override fun onTokenFinalized(token: com.kinetica.keyboard.engine.models.InputToken) { into.add(token) }
        override fun onKeyTransition(streamId: com.kinetica.keyboard.engine.models.StreamId, code: Int) {}
        override fun onAllPointersUp() {}
    }).also {
        it.maxPointers = 2
        it.setGeometry(board, 12f)
    }

    /** Pointer [pid] drawing [pts] from [t0], 8 ms a point, as a thumb on the phone; returns the lift time. */
    private fun draw(e: GestureEngine, pid: Int, pts: List<Pair<Float, Float>>, t0: Long): Long {
        val kw = gK.keyWidthPx
        e.onPointerDown(pid, pts[0].first * kw, pts[0].second * kw, t0)
        for (i in 1 until pts.size) e.onPointerMove(pid, pts[i].first * kw, pts[i].second * kw, t0 + 8L * i)
        val up = t0 + 8L * pts.size
        e.onPointerUp(pid, pts.last().first * kw, pts.last().second * kw, up)
        return up
    }

    /** One stroke drawn through the gesture engine, optionally resting on the first letter after the key. */
    private fun drawn(spelling: String, overshoot: Float, pauseAfterKey: Boolean = false): List<com.kinetica.keyboard.engine.models.InputToken> {
        val tokens = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
        draw(engine(tokens), 0, points(spelling, overshoot, pauseAfterKey), 1_000L)
        return tokens
    }

    /** The head drawn onto the key and lifted there, then the tail on the other thumb, 60 ms later. */
    private fun drawnTwo(head: String, tail: String, overshoot: Float): List<com.kinetica.keyboard.engine.models.InputToken> {
        val tokens = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
        val e = engine(tokens)
        val up = draw(e, 0, points("$head'", overshoot), 1_000L)
        draw(e, 1, points(tail, overshoot), up + 60L)
        return tokens
    }

    /** The head drawn onto the key and lifted there by one thumb, the tail by the other. */
    private fun twoThumbs(head: String, tail: String, overshoot: Float): List<com.kinetica.keyboard.engine.models.InputToken> {
        val h = TestData.sloppySwipe("$head'", gK, 1_000L, 500L, overshoot, com.kinetica.keyboard.engine.models.StreamId.RIGHT)
        val t = TestData.sloppySwipe(tail, gK, 1_560L, 500L, overshoot, com.kinetica.keyboard.engine.models.StreamId.LEFT)
        return listOf(h, t)
    }

    @Test
    fun theKeyIsAPlaceButNoTouchsKey() {
        assertTrue(gK.hasApostropheKey)
        assertFalse(g0.hasApostropheKey)
        val x = gK.centerX(a)
        val y = gK.centerY(a)
        assertEquals(9.75f, x, 1e-4f)
        assertEquals(gK.centerY(code('l')), y, 1e-4f)
        // A touch on the key is no letter's, and the nearest letter is still `l`.
        assertEquals(-1, gK.keyAt(x, y))
        assertEquals(code('l'), gK.nearestKey(x, y))
        assertEquals(code('l'), gK.letterNear(x, y))
    }

    @Test
    fun aKeyOverALetterIsNoKey() {
        // AZERTY's home row runs to the edge, so the key the layout appends lies over `m`.
        val azerty = TestData.withApostropheKey(TestData.azertyGeometry())
        assertFalse(azerty.hasApostropheKey)
    }

    @Test
    fun aStrokeThroughTheKeyPassesOverIt() {
        assertTrue(segment("don't").apostrophePasses.isNotEmpty())
        assertTrue(segment("nell'altro").apostrophePasses.isNotEmpty())
        // The same stroke on a board with no key passes over nothing.
        assertTrue(segment("don't", g0).apostrophePasses.isEmpty())
    }

    @Test
    fun aStrokeThatTurnsOnLHasNoPass() {
        // `l`'s centre lies 0.9 kw from the key's: a letter's radius would give these a pass.
        for (w in listOf("well", "still", "tell", "il")) {
            val s = segment(w, overshoot = 0.45f)
            assertTrue(w, s.apostrophePasses.isEmpty())
            assertFalse(w, s.endsOnKey)
        }
    }

    @Test
    fun anOvershootPastLIsNoVisit() {
        // Turning on `l` with 0.45 kw of overshoot brings the thumb within 0.5 kw of the key's
        // centre, but never onto the key.
        for (w in listOf("help", "slip", "elk")) {
            val s = segment(w, overshoot = 0.45f)
            assertTrue(w, s.apostrophePasses.isEmpty())
        }
    }

    @Test
    fun aLongStrokeThroughTheKeysCentreHasAPass() {
        // 32 samples over 38 kw leave none of them on the key a stroke turned on.
        val s = segment("nell'immagine", overshoot = 0f)
        assertEquals(1, s.apostrophePasses.size)
        assertTrue(s.arcLen > 30f)
    }

    @Test
    fun aStrokeLiftedJustPastLDoesNotEndOnTheKey() {
        // `e` to 0.45 kw past `l`'s centre: inside the key's radius, short of the key.
        val e = code('e')
        val l = code('l')
        val stops = listOf(gK.centerX(e) to gK.centerY(e), gK.centerX(l) + 0.45f to gK.centerY(l))
        val path = (0..20).map { i ->
            val f = i / 20f
            com.kinetica.keyboard.engine.models.PathPoint(
                stops[0].first + f * (stops[1].first - stops[0].first),
                stops[0].second + f * (stops[1].second - stops[0].second),
                1_000L + 15L * i,
            )
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        val arc = kotlin.math.hypot(stops[1].first - stops[0].first, stops[1].second - stops[0].second)
        val token = com.kinetica.keyboard.engine.models.SwipeToken(
            com.kinetica.keyboard.engine.models.StreamId.RIGHT, path, resampled, emptyList(), arc, 1_000L, 1_300L,
        )
        assertTrue(gK.distToCenter(stops[1].first, stops[1].second, a) <= KineticaConstants.APOSTROPHE_PASS_KW)
        val s = Matcher.buildSegment(token, gK)
        assertFalse(s.endsOnKey)
        assertTrue(s.apostrophePasses.isEmpty())
    }

    @Test
    fun anOvershootOntoTheKeyEndsOnIt() {
        for (w in listOf("all'", "will'", "nel'")) {
            val s = segment(w)
            assertTrue(w, s.endsOnKey)
            assertTrue(w, s.apostrophePasses.isNotEmpty())
        }
    }

    @Test
    fun aWordAfterTheKeyStartsAtTheCornerPastIt() {
        // From the key the stroke runs back along the home row to `a`, then turns: `a` begins `altro`.
        val s = segment("nell'altro")
        val pass = s.apostrophePasses[0]
        assertTrue(s.tailStartAfter(pass) > pass)
        val o = s.apostropheOrdinal(pass)
        val d = gK.distToCenter(s.apostropheTailX[o], s.apostropheTailY[o], code('a'))
        assertTrue("corner $d kw from a", d < 0.7f)
    }

    @Test
    fun aKeyedApostropheIsAVertexOfTheIdealPath() {
        val word = "don't"
        val letters = IntArray(word.length) { gK.alphabet.codeOf(word[it]) }
        val keyed = BooleanArray(word.length).also { it[3] = true }
        val dtw = DtwMatcher()
        val free = dtw.idealLength(letters, 0, letters.size, gK)
        val through = dtw.idealLength(letters, 0, letters.size, gK, keyed)
        // The key replaces the leg from `n` to `t` with two legs through it.
        val detour = gK.keyDist(code('n'), a) + gK.keyDist(a, code('t')) - gK.keyDist(code('n'), code('t'))
        assertEquals(free + detour, through, 1e-4f)
        assertTrue(detour > 1f)
    }

    @Test
    fun aContractionThroughTheKeyLeads() {
        for (w in listOf("it's", "can't", "that's", "we're", "i'd", "he's")) {
            for (o in listOf(0.25f, 0.45f)) assertEquals("$w@$o", w, lead("en", w, gK, o))
        }
    }

    @Test
    fun aContractionDrawnPastTheKeyKeepsItsLead() {
        for (w in listOf("dont", "cant", "thats", "were", "id", "hes", "well")) {
            for (o in listOf(0f, 0.45f)) assertEquals("$w@$o", lead("en", w, g0, o), lead("en", w, gK, o))
        }
    }

    @Test
    fun anOvershootOntoTheKeyKeepsThePlainWord() {
        // Lifted on the key, `well` stays `well`: letters after a keyed `'` come after the key.
        for (w in listOf("all", "will", "still", "tell", "small", "well")) {
            for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("$w@$o", w, lead("en", "$w'", gK, o))
        }
    }

    @Test
    fun anElisionThroughTheKeyGoesOnAsTheNextWord() {
        for (w in ELISIONS) {
            for (o in listOf(0.25f, 0.45f)) assertEquals("$w@$o", w, lead("it", w, gK, o))
        }
    }

    @Test
    fun anElisionKeepsItsHeadBeforeAnAccentedWord() {
        // The stroke draws `eta`; the list spells it `età`, a form of the plain key.
        for (o in listOf(0.25f, 0.45f)) assertEquals("l'età@$o", "l'età", lead("it", "l'eta", gK, o))
    }

    @Test
    fun aJoinIsMarkedOnItsCandidate() {
        val p = predictor("it")
        p.geometry = gK
        p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
        try {
            val lead = p.decode(listOf(TestData.sloppySwipe("nell'altro", gK, 1_000L, 700L, 0.25f)), emptyList()).first()
            assertEquals("nell'altro", lead.word)
            assertEquals(4, lead.joinAt)
            // A word the list spells with an accent, a form of the plain key, is marked as well.
            val accented = p.decode(listOf(TestData.sloppySwipe("l'eta", gK, 1_000L, 700L, 0.25f)), emptyList()).first()
            assertEquals("l'età", accented.word)
            assertEquals(1, accented.joinAt)
        } finally {
            p.geometry = g0
            p.elisionHeads = emptySet()
        }
        val en = predictor("en")
        en.geometry = gK
        try {
            // A contraction the trie spells is one word, keyed or not.
            val lead = en.decode(listOf(TestData.sloppySwipe("don't", gK, 1_000L, 700L, 0.25f)), emptyList()).first()
            assertEquals("don't", lead.word)
            assertEquals(-1, lead.joinAt)
        } finally {
            en.geometry = g0
        }
    }

    /**
     * An Italian or French elision stands before a vowel or a silent `h`: a stroke whose tail starts on a
     * consonant offers no join, in one stroke (the search) or across the thumbs (the beam). `l'ho` still joins.
     */
    @Test
    fun anElisionStandsOnlyBeforeAVowelOrH() {
        val tails = com.kinetica.keyboard.engine.KineticaConstants.ELISION_TAIL_LETTERS
        fun badJoins(out: List<com.kinetica.keyboard.engine.models.WordCandidate>) =
            out.filter { it.joinAt > 0 && it.word.getOrNull(it.joinAt + 1)?.let { c -> AccentFolder.fold(c.toString()) in tails.map(Char::toString) } != true }
                .map { it.word }
        val p = predictor("it")
        p.geometry = gK
        p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
        try {
            for (w in listOf("all'scusa", "dell'state", "sull'deve", "quest'mano", "l'dal")) for (o in listOf(0.25f, 0.45f)) {
                val one = p.decode(listOf(TestData.sloppySwipe(w, gK, 1_000L, 700L, o)), emptyList())
                assertEquals("$w@$o", emptyList<String>(), badJoins(one))
                val (h, t) = w.split("'")
                assertEquals("$w@$o two", emptyList<String>(), badJoins(p.decode(drawnTwo(h, t, o), emptyList())))
            }
        } finally {
            p.geometry = g0
            p.elisionHeads = emptySet()
        }
        for (o in listOf(0.25f, 0.45f)) assertEquals("l'ho@$o", "l'ho", lead("it", "l'ho", gK, o))
    }

    @Test
    fun withNoElisionHeadsTheStrokeIsOneWord() {
        val p = predictor("it")
        p.geometry = gK
        try {
            val out = p.decode(listOf(TestData.sloppySwipe("nell'altro", gK, 1_000L, 700L, 0.25f)), emptyList())
            assertTrue(out.none { it.word.startsWith("nell'") })
        } finally {
            p.geometry = g0
        }
    }

    @Test
    fun aHeadLiftedOnTheKeyAndATailOnTheOtherThumbAreOneElision() {
        for ((h, t) in listOf("nell" to "altro", "l" to "uomo", "c" to "era", "un" to "amica")) {
            for (o in listOf(0.25f, 0.45f)) assertEquals("$h'$t@$o", "$h'$t", leadOf("it", twoThumbs(h, t, o), gK))
        }
    }

    @Test
    fun anElisionInOneStrokeThroughTheKeyWritesBothWords() {
        val words = listOf(
            "nell'altro", "dell'anno", "l'uomo", "un'amica", "quest'anno", "nell'immagine", "sull'erba", "l'ho",
            "c'era", "dell'estate", "all'inizio", "d'accordo", "l'acqua", "quell'uomo", "tutt'ora", "l'altro",
            "un'altra", "all'una", "dov'era",
        )
        for (w in words) for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("$w@$o", w, leadOf("it", drawn(w, o), gK))
        // `i` lies on the way from the key to `t`, so nothing marks where the word starts but a rest on it.
        for (o in listOf(0f, 0.25f, 0.45f)) {
            assertEquals("l'italia@$o", "l'italia", leadOf("it", drawn("l'italia", o, pauseAfterKey = true), gK))
        }
    }

    @Test
    fun aStrokeOvershootingLOntoTheKeyKeepsThePlainWord() {
        // The thumb overshoots `l` toward the key and lifts on it: the letters after a keyed `'` would
        // have to come after the key, and none do.
        for ((lang, w) in listOf("en" to "well", "it" to "nel", "it" to "bel")) {
            assertEquals(w, leadOf(lang, drawn("$w'", 0.45f), gK))
        }
    }

    @Test
    fun aRestAfterTheKeyKeepsTheHeadsOwnTails() {
        // Every head's tails are read: `dell`'s are not crowded out by `dall`'s and `sull`'s before it.
        assertEquals("dell'anno", leadOf("it", drawn("dell'anno", 0.25f, pauseAfterKey = true), gK))
    }

    @Test
    fun aHeadAndAWordSpreadOverBothThumbsAreOneElision() {
        // As the phone's tries: `d-e` on the left, `l` onto the key on the right, `a` tapped on the left,
        // `n-o` on the right. `dell`'s own words are shorter than what the buffer still spells; after the
        // join the rest is a new word.
        val left = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val right = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        val toks = listOf(
            TestData.sloppySwipe("de", gK, 1_000L, 200L, 0.25f, left),
            TestData.sloppySwipe("l'", gK, 1_150L, 200L, 0.25f, right),
            TestData.tap('a', gK, 1_420L, left),
            TestData.sloppySwipe("no", gK, 1_500L, 250L, 0.25f, right),
        )
        assertEquals("dell'anno", leadOf("it", toks, gK))
    }

    @Test
    fun aHeadWhoseOwnWordsAreShortGoesOnPastTheKey() {
        // `dell`'s words end within a letter; the tail on the other thumb is longer than any of them.
        for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("dell'estate@$o", "dell'estate", leadOf("it", drawnTwo("dell", "estate", o), gK))
    }

    @Test
    fun aTailOnTheOtherThumbIsReadByTheBeam() {
        // A head lifted on the key, the tail drawn by the other thumb: the beam reads two-thumb buffers.
        for ((h, t) in listOf("dell" to "anno", "quell" to "uomo")) {
            for (o in listOf(0f, 0.25f)) assertEquals("$h'$t@$o", "$h'$t", leadOf("it", drawnTwo(h, t, o), gK))
        }
    }

    /** The beam alone over [tokens] on the keyed board, the language's heads given: its candidates by score. */
    private fun beamAlone(lang: String, tokens: List<com.kinetica.keyboard.engine.models.InputToken>): List<com.kinetica.keyboard.engine.models.WordCandidate> {
        val p = predictor(lang)
        p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf(lang))
        try {
            val seq = MergeAlternatives.sequences(tokens, DtwMatcher()).first()
            val heap = CandidateHeap(KineticaConstants.TOP_K)
            ThumbBeam(p, gK, seq, Matcher.buildPattern(seq, gK)!!, heap, -1, null).run()
            return heap.sortedByScoreDesc()
        } finally {
            p.elisionHeads = emptySet()
        }
    }

    @Test
    fun theBeamScoresAReadingThroughTheKeyAsTheSearchDoes() {
        // Its own letters draw the key, as the search's do, so the two passes merge on equal terms.
        for ((h, t) in listOf("l" to "uomo", "c" to "era", "sull" to "erba", "dell" to "anno")) {
            val toks = drawnTwo(h, t, 0f)
            val beam = beamAlone("it", toks).first { it.word == "$h'$t" }.score
            val p = predictor("it")
            p.geometry = gK
            p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
            try {
                val all = p.decode(toks, emptyList()).first { it.word == "$h'$t" }.score
                assertEquals("$h'$t", all, beam, 1e-5f)
            } finally {
                p.geometry = g0
                p.elisionHeads = emptySet()
            }
        }
    }

    @Test
    fun theBeamKeepsEveryHeadBeforeOneWord() {
        // Two heads of one length reaching one word are two readings, both offered.
        val cera = beamAlone("it", drawnTwo("c", "era", 0f)).map { it.word }
        assertTrue(cera.toString(), "c'era" in cera && "v'era" in cera)
        val erba = beamAlone("it", drawnTwo("sull", "erba", 0f)).map { it.word }
        assertTrue(erba.toString(), "s'erba" in erba && "d'erba" in erba)
    }

    @Test
    fun aContractionAcrossTheThumbsLeads() {
        // `d` tapped on the left, `o`-`n`-key drawn on the right, `t` tapped on the left.
        for ((first, stroke, last) in listOf(Triple('d', "on'", 't'), Triple('c', "an'", 't'))) {
            val toks = listOf(
                TestData.tap(first, gK, 1_000L, com.kinetica.keyboard.engine.models.StreamId.LEFT),
                TestData.sloppySwipe(stroke, gK, 1_100L, 400L, 0.25f, com.kinetica.keyboard.engine.models.StreamId.RIGHT),
                TestData.tap(last, gK, 1_560L, com.kinetica.keyboard.engine.models.StreamId.LEFT),
            )
            val word = "$first${stroke}$last"
            assertEquals(word, word, beamAlone("en", toks).firstOrNull()?.word)
            assertEquals(word, word, leadOf("en", toks, gK))
        }
    }

    /**
     * `marco's` across the thumbs as the alpha1 gate typed it: `m` on the right, `a r c` tapped on the left,
     * the right stroke `o` `l` onto the key, `s` on the left. The key is touched about 500 ms after `a` and
     * `r`: an elision over it (`mo'arca`, `all'arca`) would read them after it.
     */
    private fun marcosTwoThumbs(): List<com.kinetica.keyboard.engine.models.InputToken> {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        return listOf(
            TestData.tap('m', gK, 1_000L, r),
            TestData.tap('a', gK, 1_093L, l),
            TestData.tap('r', gK, 1_279L, l),
            TestData.sloppySwipe("ol'", gK, 1_459L, 520L, 0.25f, r),
            TestData.tap('c', gK, 1_472L, l),
            TestData.tap('s', gK, 1_931L, l),
        )
    }

    @Test
    fun aKeyedApostropheTakesNoLetterTypedBeforeIt() {
        // Every letter but `s` came before the key: no elision can be read, only words without `'`.
        val p = predictor("it")
        p.geometry = gK
        p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
        try {
            val joined = p.decode(marcosTwoThumbs(), emptyList()).map { it.word }.filter { '\'' in it }
            assertTrue(joined.toString(), joined.isEmpty())
        } finally {
            p.geometry = g0
            p.elisionHeads = emptySet()
        }
    }

    /** The lead of one engine-drawn stroke in English, `'s` after list words on. */
    private fun possessiveLead(spelling: String, o: Float): String? {
        val p = predictor("en")
        p.possessive = true
        try {
            return leadOf("en", drawn(spelling, o), gK)
        } finally {
            p.possessive = false
        }
    }

    @Test
    fun aListWordTakesItsPossessiveThroughTheKey() {
        for (w in listOf("mexico's", "john's", "today's", "world's", "kevin's")) {
            for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("$w@$o", w, possessiveLead(w, o))
        }
    }

    /** [word] drawn onto the key and lifted there, then `s` tapped by the left thumb 80 ms later. */
    private fun strokeThenS(word: String, overshoot: Float): List<com.kinetica.keyboard.engine.models.InputToken> {
        val tokens = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
        val up = draw(engine(tokens), 0, points("$word'", overshoot), 1_000L)
        return tokens + TestData.tap('s', gK, up + 80L, com.kinetica.keyboard.engine.models.StreamId.LEFT)
    }

    /** The English lead of [tokens], `'s` after list words on, as the keyboard sets it. */
    private fun possessiveLeadOf(tokens: List<com.kinetica.keyboard.engine.models.InputToken>): String? {
        val p = predictor("en")
        p.possessive = true
        try {
            return leadOf("en", tokens, gK)
        } finally {
            p.possessive = false
        }
    }

    @Test
    fun aListWordTakesItsPossessiveAcrossTheThumbs() {
        // The word drawn onto the key by one thumb, `s` tapped by the other: the beam reads `'s` as the
        // search does in one stroke.
        for (w in listOf("mexico", "peter", "london", "today")) {
            for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("$w@$o", "$w's", possessiveLeadOf(strokeThenS(w, o)))
        }
        // `m` on the right, `a r c` on the left, `o` `l` onto the key on the right, `s` on the left.
        assertEquals("marco's", possessiveLeadOf(marcosTwoThumbs()))
    }

    /**
     * One thumb draws the word, the other taps `'` while it is down, wherever the thumb is free: `c'era` with
     * the tap between `r` and `a`, `peter's` with `p` tapped first and the tap near the stroke's end. The tap
     * belongs to the stroke's word, not to its place in time.
     */
    @Test
    fun aTappedApostropheInsideTheOtherStrokeRidesIt() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        for (at in listOf(1_150L, 1_450L, 1_560L)) {
            val cera = listOf(TestData.sloppySwipe("cera", gK, 1_000L, 600L, 0.25f, l), TestData.tap('\'', gK, at, r))
            assertEquals("c'era@$at", "c'era", leadOf("it", cera, gK))
        }
        // Elisions drawn whole by one thumb, the tap at a third and two thirds of the stroke. A head ending
        // in a doubled letter (`nell`, `dell`, `sull`) is not read this way: with no key the join has no
        // place, and a one-letter head takes the stroke (`n'elaborato`). Drawn onto the key, they are.
        for (w in listOf("c'era", "l'uomo", "l'acqua", "un'amica", "quest'anno")) for (at in listOf(1_200L, 1_400L)) {
            val toks = listOf(TestData.sloppySwipe(w.replace("'", ""), gK, 1_000L, 600L, 0.25f, l), TestData.tap('\'', gK, at, r))
            assertEquals("$w@$at", w, leadOf("it", toks, gK))
            // The tap is never lost: every word offered holds it.
            val p = predictor("it")
            p.geometry = gK
            p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
            p.ridesApostrophes = true
            try {
                val bare = p.decode(toks, emptyList()).map { it.word }.filter { '\'' !in it }
                assertTrue("$w@$at $bare", bare.isEmpty())
            } finally {
                p.geometry = g0
                p.elisionHeads = emptySet()
                p.ridesApostrophes = false
            }
        }
        for (at in listOf(1_300L, 1_620L, 1_800L)) {
            val peters = listOf(
                TestData.tap('p', gK, 1_000L, r),
                TestData.sloppySwipe("eters", gK, 1_070L, 780L, 0.25f, l),
                TestData.tap('\'', gK, at, r),
            )
            assertEquals("peter's@$at", "peter's", possessiveLeadOf(peters))
        }
    }

    /**
     * A predictor not asked to ride reads the tap where it falls in time, as a tap always was: no reading
     * carries it into the stroke's word.
     */
    @Test
    fun aTappedApostropheRidesOnlyWhenAsked() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        val p = predictor("it")
        p.geometry = gK
        p.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
        try {
            for (at in listOf(1_150L, 1_450L, 1_560L)) {
                val cera = listOf(TestData.sloppySwipe("cera", gK, 1_000L, 600L, 0.25f, l), TestData.tap('\'', gK, at, r))
                p.ridesApostrophes = false
                val off = p.decode(cera, emptyList()).map { it.word }
                assertFalse("c'era@$at $off", "c'era" in off)
                assertEquals("riding left set", 0, p.carried)
                p.ridesApostrophes = true
                assertEquals("c'era@$at", "c'era", p.decode(cera, emptyList()).firstOrNull()?.word)
            }
        } finally {
            p.geometry = g0
            p.elisionHeads = emptySet()
            p.ridesApostrophes = false
        }
    }

    /**
     * Each search alone reads a carried `'`: the passes with the beam off, the beam on the buffer the passes
     * see. Neither offers a word without it.
     */
    @Test
    fun eachSearchAloneReadsACarriedApostrophe() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        val cera = listOf(TestData.sloppySwipe("cera", gK, 1_000L, 600L, 0.25f, l))
        val peter = listOf(TestData.tap('p', gK, 1_000L, r), TestData.sloppySwipe("eters", gK, 1_070L, 780L, 0.25f, l))
        val it = predictor("it")
        val en = predictor("en")
        try {
            it.geometry = gK
            it.elisionHeads = com.kinetica.keyboard.ime.elisionHeadsFor(listOf("it"))
            en.geometry = gK
            en.possessive = true
            it.ridesApostrophes = true
            en.ridesApostrophes = true
            val tap = TestData.tap('\'', gK, 1_450L, r)
            val passesCera = it.decode(cera + tap, emptyList(), beam = false).map { it.word }
            assertEquals("c'era", passesCera.firstOrNull())
            assertTrue(passesCera.toString(), passesCera.all { '\'' in it })
            val passesPeter = en.decode(peter + TestData.tap('\'', gK, 1_620L, r), emptyList(), beam = false).map { it.word }
            assertEquals("peter's", passesPeter.firstOrNull())
            assertTrue(passesPeter.toString(), passesPeter.all { '\'' in it })
            it.carried = 1
            en.carried = 1
            val beamCera = beamAlone("it", cera).map { it.word }
            assertEquals("c'era", beamCera.firstOrNull())
            assertTrue(beamCera.toString(), beamCera.all { '\'' in it })
            val beamPeter = beamOf(en, peter).map { it.word }
            assertEquals("peter's", beamPeter.firstOrNull())
            assertTrue(beamPeter.toString(), beamPeter.all { '\'' in it })
        } finally {
            it.carried = 0
            en.carried = 0
            it.geometry = g0
            it.elisionHeads = emptySet()
            en.geometry = g0
            en.possessive = false
            it.ridesApostrophes = false
            en.ridesApostrophes = false
        }
    }

    /**
     * The touch aimed at the key lands on `l` beside it and slides on: `p` tapped, `e t e r s` drawn by the
     * left thumb, the right lifting on the key while the left is down. Read as the key tapped, it rides.
     */
    @Test
    fun aTouchOnLThatLiftsOnTheKeyIsTheKey() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        val y = gK.centerY(code('l'))
        for (startX in listOf(9.15f, 9.25f)) {
            val touch = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
            val e = engine(touch)
            draw(e, 1, (0..7).map { i -> startX + (9.75f - startX) * i / 7f to y + 0.25f * i / 7f }, 1_500L)
            assertTrue(touch.toString(), touch.single() is com.kinetica.keyboard.engine.models.SwipeToken)
            val tokens = listOf(TestData.tap('p', gK, 1_000L, r), TestData.sloppySwipe("eters", gK, 1_070L, 780L, 0.25f, l)) + touch
            assertEquals("$startX", "peter's", possessiveLeadOf(tokens))
        }
    }

    /**
     * With the apostrophe in strokes off the engine never sees the key: the same touch on `l` lifted where
     * the key is drawn reads no `'`, and the word is not `peter's`. The board alone decides it: the
     * predictor here reads `'s` and riding taps as with the switch on.
     */
    /**
     * The thumb lands on `l` and slides onto the key with a wobble, about 1.5 kw drawn, while the other thumb
     * still draws: still the key touched, not `l` (`petals`).
     */
    @Test
    fun aLongerSlideFromLOntoTheKeyIsTheKey() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        val y = gK.centerY(code('l'))
        val legs = listOf(8.6f to y, 9.0f to y + 0.3f, 9.4f to y, 9.8f to y + 0.2f)
        val pts = legs.zipWithNext().flatMap { (p, q) -> (0 until 6).map { i -> p.first + (q.first - p.first) * i / 6f to p.second + (q.second - p.second) * i / 6f } } + legs.last()
        val touch = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
        draw(engine(touch), 1, pts, 1_500L)
        val swipe = touch.single() as com.kinetica.keyboard.engine.models.SwipeToken
        assertTrue("arc ${swipe.arcLen}", swipe.arcLen > 1.2f && swipe.arcLen < KineticaConstants.APOSTROPHE_TOUCH_ARC_KW)
        val tokens = listOf(TestData.tap('p', gK, 1_000L, r), TestData.sloppySwipe("eters", gK, 1_070L, 780L, 0.25f, l)) + touch
        assertEquals("peter's", possessiveLeadOf(tokens))
    }

    @Test
    fun withStrokesOffATouchLiftedOnTheKeyIsNoApostrophe() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        val keyKw = gK.rectKw(a)
        val off = com.kinetica.keyboard.ui.TouchRules.engineBoard(g0, keyKw, strokes = false)
        val y = gK.centerY(code('l'))
        val en = predictor("en")
        try {
            com.kinetica.keyboard.ime.strokeApostrophes(en, true)
            en.geometry = off
            for (startX in listOf(9.15f, 9.25f)) {
                val touch = ArrayList<com.kinetica.keyboard.engine.models.InputToken>()
                draw(engine(touch, off), 1, (0..7).map { i -> startX + (9.75f - startX) * i / 7f to y + 0.25f * i / 7f }, 1_500L)
                val tokens = listOf(TestData.tap('p', off, 1_000L, r), TestData.sloppySwipe("eters", off, 1_070L, 780L, 0.25f, l)) + touch
                val words = en.decode(tokens, emptyList()).map { it.word }
                assertTrue("$startX $words", words.none { '\'' in it })
            }
        } finally {
            com.kinetica.keyboard.ime.strokeApostrophes(en, false)
            en.geometry = g0
        }
    }

    /**
     * The `'` tapped after the stroke lifted, then `s`: `p` tapped, `e t e r` drawn, `'`, `s` tapped. The
     * decode reads it once `s` comes; before that the buffer holds the word and its `'`, with no reading.
     */
    @Test
    fun aTappedApostropheAfterALiftedStrokeTakesItsS() {
        val l = com.kinetica.keyboard.engine.models.StreamId.LEFT
        val r = com.kinetica.keyboard.engine.models.StreamId.RIGHT
        for (w in listOf("peter", "london")) {
            val head = if (w == "peter") listOf(TestData.tap('p', gK, 1_000L, r), TestData.sloppySwipe("eter", gK, 1_070L, 600L, 0.25f, l))
            else listOf(TestData.sloppySwipe(w, gK, 1_000L, 670L, 0.25f, r))
            val tokens = head + TestData.tap('\'', gK, 1_760L, r) + TestData.tap('s', gK, 1_900L, l)
            assertEquals(w, "$w's", possessiveLeadOf(tokens))
        }
    }

    /** The beam alone on [tokens] with [p] as it stands. */
    private fun beamOf(p: WordPredictor, tokens: List<com.kinetica.keyboard.engine.models.InputToken>): List<com.kinetica.keyboard.engine.models.WordCandidate> {
        val seq = MergeAlternatives.sequences(tokens, DtwMatcher()).first()
        val heap = CandidateHeap(KineticaConstants.TOP_K)
        ThumbBeam(p, gK, seq, Matcher.buildPattern(seq, gK)!!, heap, -1, null).run()
        return heap.sortedByScoreDesc()
    }

    @Test
    fun aPossessiveBreaksNoGateTheSearchRefuses() {
        // The beam reads one stroke too: `the` and `'s` on a `there's` stroke skip `r e` and break its end.
        for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("there's@$o", "there's", possessiveLead("there's", o))
    }

    @Test
    fun aContractionTheListSpellsBeatsAPossessiveItDoesNot() {
        // `her's` is a list word and `'s`; `he's` is in the list: the list's own spelling wins.
        for (o in listOf(0f, 0.25f, 0.45f)) assertEquals("he's@$o", "he's", possessiveLead("he's", o))
        // A word that already holds `'` takes no second one.
        val p = predictor("en")
        p.possessive = true
        p.geometry = gK
        try {
            val all = p.decode(drawn("he's", 0.45f), emptyList()).map { it.word }
            assertTrue(all.toString(), all.none { w -> w.count { it == '\'' } > 1 })
        } finally {
            p.possessive = false
            p.geometry = g0
        }
    }

    @Test
    fun aCapturesBoardLineCarriesTheKey() {
        val line = GestureEngine.geometryLine(gK, 0.3f)
        assertTrue(line, line.endsWith(";':9.50,1.50,10.00,3.00"))
        val back = TraceReplay.board(line)!!.g
        assertTrue(back.hasApostropheKey)
        assertEquals(gK.centerX(a), back.centerX(a), 0.01f)
        assertEquals(gK.centerY(a), back.centerY(a), 0.01f)
        // No key on the board, none in the line.
        assertFalse(GestureEngine.geometryLine(g0, 0.3f).contains('\''))
    }
}
