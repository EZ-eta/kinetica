package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalWordRowsTest {

    @Test
    fun theMergeFloorDecidesWhatIsInDecode() {
        // The screen exists to tell a harmful row from an inert one.
        assertFalse("a de-reinforced row is inert", PersonalWordRows.isInDecode(0))
        assertFalse("one stray commit never reaches the trie", PersonalWordRows.isInDecode(1))
        assertTrue(PersonalWordRows.isInDecode(KineticaConstants.PERSONAL_MERGE_MIN_COUNT))
        assertTrue(PersonalWordRows.isInDecode(6))
        // Tied to the constant, not to a literal: if the floor ever moves, the
        // label moves with it.
        assertEquals(
            "the boundary must be the merge floor itself",
            KineticaConstants.PERSONAL_MERGE_MIN_COUNT,
            (0..20).first { PersonalWordRows.isInDecode(it) },
        )
    }

    @Test
    fun theMostInfluentialRowsComeFirst() {
        // "cuñado" at 6 stands for a self-reinforced misfire that outranked the intended word;
        // alphabetical order would bury it between "casa" and "dormir".
        val rows = listOf("dormir" to 2, "casa" to 1, "cuñado" to 6, "abeja" to 6, "zorro" to 3)
        assertEquals(
            listOf("abeja" to 6, "cuñado" to 6, "zorro" to 3, "dormir" to 2, "casa" to 1),
            PersonalWordRows.sortedForDisplay(rows),
        )
    }

    @Test
    fun sortingIsStableAndTotalOnDegenerateInput() {
        assertEquals(emptyList<Pair<String, Int>>(), PersonalWordRows.sortedForDisplay(emptyList()))
        // Equal counts fall back to the word, so the list never reorders between
        // two openings of the screen.
        val same = listOf("b" to 3, "a" to 3, "c" to 3)
        assertEquals(listOf("a" to 3, "b" to 3, "c" to 3), PersonalWordRows.sortedForDisplay(same))
        assertEquals(
            PersonalWordRows.sortedForDisplay(same),
            PersonalWordRows.sortedForDisplay(same.reversed()),
        )
    }

    // ---- the search filter --------------------------------------------------

    /**
     * The list the dialog is built from, count-ordered as the screen shows it. Alphabetical and
     * count order disagree here, so a filter that re-sorted would be caught.
     */
    private val dictionary = PersonalWordRows.sortedForDisplay(
        listOf(
            "che" to 314, "me" to 69, "sempre" to 45, "mo" to 8,
            "perché" to 30, "meno" to 12, "qd" to 3, "come" to 77,
        ),
    )

    @Test
    fun tappingAFilteredRowResolvesToTheWordUnderTheFinger() {
        // The filter's main risk: an index resolved against the unfiltered list deletes
        // whatever sits at that visual position, so the screen meant to remove one harmful word
        // would remove a different one.
        val shown = PersonalWordRows.filtered(dictionary, "me")
        // "me" itself is not first: count ordering puts "come" (77) above it.
        assertEquals(listOf("come", "me", "meno"), shown.map { it.first })
        // Position 1 in the filtered list is "me"; in the unfiltered list it is "come". A
        // handler using the raw index would delete the wrong one.
        assertEquals("me", shown[1].first)
        assertEquals("come", dictionary[1].first)
        // The row must carry its own count too, or the confirm dialog would
        // quote the wrong number.
        assertEquals(69, shown[1].second)
    }

    @Test
    fun theFilterPreservesCountOrdering() {
        // Influence first (see sortedForDisplay), because that is what a poisoning word has; a
        // filter returning matches in dictionary order would silently undo it.
        val shown = PersonalWordRows.filtered(dictionary, "e")
        assertEquals(
            listOf("che", "come", "me", "sempre", "perché", "meno"),
            shown.map { it.first },
        )
        assertEquals(shown, shown.sortedByDescending { it.second })
    }

    @Test
    fun aBlankQueryIsTheWholeList() {
        // Clearing the box restores everything, including the empty and
        // whitespace-only cases the EditText can produce mid-edit.
        for (q in listOf("", " ", "   ", "\t")) {
            assertEquals("query '$q' must not filter", dictionary, PersonalWordRows.filtered(dictionary, q))
        }
    }

    @Test
    fun theFilterIgnoresCaseAndAccents() {
        // Otherwise "perché" cannot be found by typing "perche". Folding is AccentFolder's, as in
        // the decoder, so the two agree about what a letter is.
        assertEquals(listOf("perché"), PersonalWordRows.filtered(dictionary, "perche").map { it.first })
        assertEquals(listOf("perché"), PersonalWordRows.filtered(dictionary, "PERCHÉ").map { it.first })
        assertEquals(listOf("che", "perché"), PersonalWordRows.filtered(dictionary, "CHE").map { it.first })
    }

    @Test
    fun aLowCountMatchIsStillReportedAsBelowTheFloor() {
        // Filtering must not change what a row means: "qd", a junk word, found by name still
        // shows as inert, not as a decode participant.
        val shown = PersonalWordRows.filtered(dictionary, "qd")
        assertEquals(listOf("qd" to 3), shown)
        assertTrue("qd at 3 is above the floor", PersonalWordRows.isInDecode(shown[0].second))
        val inert = PersonalWordRows.filtered(listOf("qd" to 1), "q")
        assertFalse("a count-1 row stays inert under a filter", PersonalWordRows.isInDecode(inert[0].second))
    }

    @Test
    fun noMatchIsAnEmptyListAndNotTheWholeDictionary() {
        // A careless filter falls through to everything on no match, putting thousands of rows
        // back on screen.
        assertEquals(emptyList<Pair<String, Int>>(), PersonalWordRows.filtered(dictionary, "zzz"))
    }

    // ------------------------------------------------- batch selection
    //
    // Multi-select, because deleting one word at a time closed and moved the dialog on every
    // press. It inherits the screen's hazard: a tick is a position in the filtered list.

    @Test
    fun aCheckedWordSurvivesAFilterChange() {
        // "meno" sits at a different index in the full list and in the "me" view, and the
        // tick has to follow the word through both.
        val checked = setOf("meno")
        val narrowed = PersonalWordRows.filtered(dictionary, "me")
        assertEquals(
            listOf("meno"),
            PersonalWordRows.checkedPositions(dictionary, checked).map { dictionary[it].first },
        )
        assertEquals(
            listOf("meno"),
            PersonalWordRows.checkedPositions(narrowed, checked).map { narrowed[it].first },
        )
        assertNotEquals(
            PersonalWordRows.checkedPositions(dictionary, checked),
            PersonalWordRows.checkedPositions(narrowed, checked),
        )
    }

    @Test
    fun checkedRowsResolveToWordsNotPositions() {
        // The defect this pins: index 0 of the "me" view is "come", index 0 of the full
        // list is "che", so a position-keyed selection deletes the wrong word.
        val narrowed = PersonalWordRows.filtered(dictionary, "me")
        val firstShown = narrowed.first().first
        val positions = PersonalWordRows.checkedPositions(narrowed, setOf(firstShown))
        assertEquals(listOf(firstShown), positions.map { narrowed[it].first })
        assertNotEquals(firstShown, dictionary[positions.first()].first)
    }

    @Test
    fun aBatchDeleteRemovesEveryCheckedWordAndNothingElse() {
        val checked = setOf(dictionary[0].first, dictionary[2].first)
        assertEquals(
            listOf(dictionary[0].first, dictionary[2].first),
            PersonalWordRows.wordsToDelete(dictionary, checked),
        )
    }

    @Test
    fun aCheckedWordThatIsNoLongerARowIsNotDeleted() {
        // A tick outlives its row: the list is rebuilt after each batch, so the set can
        // still name something already gone.
        assertEquals(
            emptyList<String>(),
            PersonalWordRows.wordsToDelete(dictionary, setOf("notinthelist")),
        )
    }

    @Test
    fun nothingCheckedDeletesNothing() {
        assertEquals(emptyList<String>(), PersonalWordRows.wordsToDelete(dictionary, emptySet()))
        assertEquals(emptyList<Int>(), PersonalWordRows.checkedPositions(dictionary, emptySet()))
    }

    @Test
    fun aRowSaysWhetherItIsSuggestedAndWhenItWillBe() {
        // The label says what the merge floor means for the word, not "below the merge floor".
        val floor = com.kinetica.keyboard.engine.KineticaConstants.PERSONAL_MERGE_MIN_COUNT
        assertEquals(PersonalWordRows.RowState.SUGGESTED, PersonalWordRows.rowState(floor))
        assertEquals(PersonalWordRows.RowState.SUGGESTED, PersonalWordRows.rowState(40))
        assertEquals(PersonalWordRows.RowState.BELOW_FLOOR, PersonalWordRows.rowState(floor - 1))
        assertEquals(PersonalWordRows.RowState.TAKEN_BACK, PersonalWordRows.rowState(0))
    }
}
