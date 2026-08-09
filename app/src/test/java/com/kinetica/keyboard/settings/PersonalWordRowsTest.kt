package com.kinetica.keyboard.settings

import com.kinetica.keyboard.engine.KineticaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalWordRowsTest {

    @Test
    fun theMergeFloorDecidesWhatIsInDecode() {
        // The screen's whole point is telling a harmful row from an inert one.
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
        // "cuñado" at 6 is the historical case: a
        // self-reinforced misfire that outranked the intended word. Alphabetical
        // order would bury it between "casa" and "dormir".
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
     * The list the dialog is built from, count-ordered as the screen shows it.
     * Deliberately one where alphabetical and count order disagree, so a filter
     * that quietly re-sorted would be caught.
     */
    private val dictionary = PersonalWordRows.sortedForDisplay(
        listOf(
            "che" to 314, "me" to 69, "sempre" to 45, "mo" to 8,
            "perché" to 30, "meno" to 12, "qd" to 3, "come" to 77,
        ),
    )

    @Test
    fun tappingAFilteredRowResolvesToTheWordUnderTheFinger() {
        // THE risk this feature carries, and the reason it is the first test.
        // The dialog used to be built with setItems over the whole list, so the
        // click index WAS the index into that list. With a filter in front, an
        // index resolved against the unfiltered list deletes whatever happens to
        // sit at that visual position - i.e. the screen whose entire purpose is
        // removing one specific harmful word would remove a different one.
        val shown = PersonalWordRows.filtered(dictionary, "me")
        // "me" itself is not first: count ordering puts "come" (77) above it.
        assertEquals(listOf("come", "me", "meno"), shown.map { it.first })
        // Position 1 in the FILTERED list is "me"; position 1 in the unfiltered
        // list is "come". A handler that used the raw index would delete the
        // wrong one, and these two words are exactly the pair the boost rule cares
        // about.
        assertEquals("me", shown[1].first)
        assertEquals("come", dictionary[1].first)
        // The row must carry its own count too, or the confirm dialog would
        // quote the wrong number.
        assertEquals(69, shown[1].second)
    }

    @Test
    fun theFilterPreservesCountOrdering() {
        // Ordering is the screen's one design decision (see sortedForDisplay):
        // influence first, because that is what a poisoning word has. A filter
        // that returned matches in dictionary order would silently undo it.
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
        // "perché" is unreachable by typing "perche" otherwise, and the whole
        // point is finding a word you can name. Folding is AccentFolder's, the
        // same one the decoder uses, so the two agree about what a letter is.
        assertEquals(listOf("perché"), PersonalWordRows.filtered(dictionary, "perche").map { it.first })
        assertEquals(listOf("perché"), PersonalWordRows.filtered(dictionary, "PERCHÉ").map { it.first })
        assertEquals(listOf("che", "perché"), PersonalWordRows.filtered(dictionary, "CHE").map { it.first })
    }

    @Test
    fun aLowCountMatchIsStillReportedAsBelowTheFloor() {
        // Filtering must not change what a row MEANS. "qd" is the historical
        // junk word and finding it by name must still
        // show it as inert rather than as a decode participant.
        val shown = PersonalWordRows.filtered(dictionary, "qd")
        assertEquals(listOf("qd" to 3), shown)
        assertTrue("qd at 3 is above the floor", PersonalWordRows.isInDecode(shown[0].second))
        val inert = PersonalWordRows.filtered(listOf("qd" to 1), "q")
        assertFalse("a count-1 row stays inert under a filter", PersonalWordRows.isInDecode(inert[0].second))
    }

    @Test
    fun noMatchIsAnEmptyListAndNotTheWholeDictionary() {
        // The failure mode of a badly-written filter: fall through to
        // "everything" on no match, which would put 4,266 rows back on screen.
        assertEquals(emptyList<Pair<String, Int>>(), PersonalWordRows.filtered(dictionary, "zzz"))
    }
}
