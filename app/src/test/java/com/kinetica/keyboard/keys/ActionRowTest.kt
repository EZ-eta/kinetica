package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut row's four decisions: which actions exist, what each draws, what order they
 * come in, and which are worth offering. Neither surface that renders them has a JVM test
 * harness, so this is where they are pinned.
 */
class ActionRowTest {

    @Test
    fun everyActionIsOfferable() {
        // An action the row could not offer would be a silent gap between the pickers and
        // this list, the bug the edge-swipe editor once had.
        assertEquals(EditorAction.entries.toSet(), ActionRow.ALL.toSet())
        assertEquals(ActionRow.ALL.size, ActionRow.ALL.distinct().size)
    }

    @Test
    fun everyGlyphIsShortAndDistinct() {
        // The ?123 popup draws a cell with no measuring and no clipping, so a long label
        // overlaps its neighbours instead of shrinking. Three characters is the widest
        // thing proven to work there.
        val glyphs = ActionRow.ALL.map { ActionRow.glyph(it) }
        for (g in glyphs) {
            assertTrue(g, g.isNotEmpty() && g.length <= 3)
        }
        assertEquals(glyphs.size, glyphs.distinct().size)
    }

    @Test
    fun theDefaultsAreTheFourAtTheFront() {
        // New actions append, so turning one on does not move cells a user already knows.
        assertEquals(ActionRow.DEFAULT, ActionRow.ALL.take(4).map { it.name }.toSet())
        for (name in ActionRow.DEFAULT) {
            assertTrue(name, ActionRow.ALL.any { it.name == name })
        }
    }

    @Test
    fun theUsersOrderIsTheRowsOrder() {
        val chosen = setOf("EXPANDIFY", "SETTINGS", "UNDO")
        assertEquals(
            listOf(EditorAction.UNDO, EditorAction.EXPANDIFY, EditorAction.SETTINGS),
            ActionRow.resolve(chosen, 2, 10, order = listOf("UNDO", "EXPANDIFY", "SETTINGS")),
        )
    }

    @Test
    fun aChosenActionTheOrderMissesComesAfterInCanonicalOrder() {
        // A selection made before ordering existed, or an action turned on elsewhere.
        assertEquals(
            listOf(EditorAction.UNDO, EditorAction.SETTINGS, EditorAction.EXPANDIFY),
            ActionRow.ordered(setOf("EXPANDIFY", "SETTINGS", "UNDO"), listOf("UNDO")),
        )
    }

    @Test
    fun unknownAndUnchosenNamesInTheOrderAreDropped() {
        // An order written by another build, or naming an action turned off since.
        assertEquals(
            listOf(EditorAction.PASTE, EditorAction.SETTINGS),
            ActionRow.ordered(setOf("SETTINGS", "PASTE"), listOf("GONE", "PASTE", "UNDO", "PASTE", "SETTINGS")),
        )
    }

    @Test
    fun theOrderIsCutAfterOrderingNotBefore() {
        // The first ones the user put in front are the ones a narrow bar keeps.
        assertEquals(
            listOf(EditorAction.PASTE),
            ActionRow.resolve(setOf("SETTINGS", "PASTE"), 2, maxCells = 1, order = listOf("PASTE")),
        )
    }

    @Test
    fun anOrderRoundTripsThroughItsStoredForm() {
        val order = listOf(EditorAction.TIME, EditorAction.PASTE)
        assertEquals(listOf("TIME", "PASTE"), ActionRow.decodeOrder(ActionRow.encodeOrder(order)))
        assertEquals(emptyList<String>(), ActionRow.decodeOrder(null))
        assertEquals(emptyList<String>(), ActionRow.decodeOrder(" , "))
    }

    @Test
    fun theRowIsCanonicallyOrderedWhateverOrderItIsChosenIn() {
        // With no stored order, the set carries membership and the order is ALL's.
        // A Set has no iteration order to trust.
        val chosen = setOf("EXPANDIFY", "SETTINGS", "UNDO")
        assertEquals(
            listOf(EditorAction.SETTINGS, EditorAction.UNDO, EditorAction.EXPANDIFY),
            ActionRow.resolve(chosen, enabledLanguages = 2, maxCells = 10),
        )
    }

    @Test
    fun theLanguageCellDropsOutBelowTwoLanguages() {
        // cycleLanguage returns silently with one language, so the cell would be a button
        // that does nothing. The spacebar hides its language code on the same test.
        assertFalse(ActionRow.available(EditorAction.NEXT_LANGUAGE, enabledLanguages = 1))
        assertTrue(ActionRow.available(EditorAction.NEXT_LANGUAGE, enabledLanguages = 2))
        assertEquals(
            listOf(EditorAction.SETTINGS),
            ActionRow.resolve(setOf("SETTINGS", "NEXT_LANGUAGE"), 1, 10),
        )
    }

    @Test
    fun everyOtherActionIsAlwaysAvailable() {
        for (a in ActionRow.ALL) {
            if (a == EditorAction.NEXT_LANGUAGE) continue
            assertTrue(a.name, ActionRow.available(a, enabledLanguages = 1))
        }
    }

    @Test
    fun theRowIsCutToWhatFits() {
        // A cell narrower than a thumb is not a shortcut. The bar keeps the front of the row
        // instead of shrinking all of it.
        assertEquals(2, ActionRow.resolve(ActionRow.DEFAULT, 2, maxCells = 2).size)
        assertEquals(emptyList<EditorAction>(), ActionRow.resolve(ActionRow.DEFAULT, 2, 0))
    }

    @Test
    fun cellsThatFitIsTheBarsOwnArithmetic() {
        assertEquals(5, ActionRow.cellsThatFit(availablePx = 360f, minCellPx = 72f))
        assertEquals(4, ActionRow.cellsThatFit(availablePx = 359f, minCellPx = 72f))
        assertEquals(0, ActionRow.cellsThatFit(availablePx = 10f, minCellPx = 72f))
        // Before layout the width is zero, and zero cells is the right answer then.
        assertEquals(0, ActionRow.cellsThatFit(availablePx = 0f, minCellPx = 72f))
        assertEquals(0, ActionRow.cellsThatFit(availablePx = 360f, minCellPx = 0f))
    }

    // ---- the one-handed toggle -------------------------------------------------------

    @Test
    fun leavingOneHandedRemembersWhichItWas() {
        // A left-hander who toggles off and on must not be handed the right-hand default.
        val off = ActionRow.oneHandedToggle(current = "left", remembered = null)
        assertEquals(ActionRow.FULL, off.mode)
        assertEquals("left", off.remember)
        val on = ActionRow.oneHandedToggle(current = ActionRow.FULL, remembered = "left")
        assertEquals("left", on.mode)
    }

    @Test
    fun theFirstToggleWithNothingRememberedUsesTheNamedMode() {
        val on = ActionRow.oneHandedToggle(current = ActionRow.FULL, remembered = null)
        assertEquals(ActionRow.DEFAULT_ONE_HANDED, on.mode)
    }

    @Test
    fun aRememberedFullIsIgnoredSoTheToggleCannotStick() {
        // Writing "full" into the memory would make the toggle a no-op forever.
        val on = ActionRow.oneHandedToggle(ActionRow.FULL, remembered = ActionRow.FULL)
        assertEquals(ActionRow.DEFAULT_ONE_HANDED, on.mode)
    }

    @Test
    fun turningItOffNeverOverwritesTheMemoryWithFull() {
        assertEquals(null, ActionRow.oneHandedToggle(ActionRow.FULL, "split").remember)
        assertEquals("split", ActionRow.oneHandedToggle("split", null).remember)
    }

    // ---- the spacebar notice

    private fun state(
        autospace: Boolean = true,
        languages: List<String> = listOf("en", "it"),
        language: String = "en",
        layoutMode: String = ActionRow.FULL,
        remembered: String? = null,
    ) = ActionRow.KeyboardState(autospace, languages, language, layoutMode, remembered)

    @Test
    fun aStateToggleSaysTheStateItLeavesBehind() {
        assertEquals(
            ActionRow.Notice.Autospace(on = false),
            ActionRow.notice(EditorAction.TOGGLE_AUTOSPACE, state(autospace = true)),
        )
        assertEquals(
            ActionRow.Notice.Autospace(on = true),
            ActionRow.notice(EditorAction.TOGGLE_AUTOSPACE, state(autospace = false)),
        )
        assertEquals(
            ActionRow.Notice.Language("it"),
            ActionRow.notice(EditorAction.NEXT_LANGUAGE, state(language = "en")),
        )
        assertEquals(
            ActionRow.Notice.Layout("left"),
            ActionRow.notice(EditorAction.ONE_HANDED, state(remembered = "left")),
        )
        assertEquals(
            ActionRow.Notice.Layout(ActionRow.FULL),
            ActionRow.notice(EditorAction.ONE_HANDED, state(layoutMode = "left")),
        )
    }

    @Test
    fun anEditorCommandSaysItsNameAndSettingsSaysNothing() {
        // The app reports delivery, not effect, so a command can only name itself.
        for (a in listOf(
            EditorAction.UNDO, EditorAction.REDO, EditorAction.PASTE, EditorAction.COPY,
            EditorAction.CUT, EditorAction.SELECT_ALL, EditorAction.RETYPE,
            EditorAction.EXPANDIFY,
        )) {
            assertEquals(a.name, ActionRow.Notice.Sent(a), ActionRow.notice(a, state()))
        }
        assertEquals(null, ActionRow.notice(EditorAction.SETTINGS, state()))
    }

    @Test
    fun theNoticeNamesTheLanguageTheCycleActuallyPicks() {
        // One function behind both, so they cannot drift.
        assertEquals("en", ActionRow.nextLanguage(listOf("en", "it", "pl"), "pl"))
        assertEquals("pl", ActionRow.nextLanguage(listOf("en", "it", "pl"), "it"))
        assertEquals("en", ActionRow.nextLanguage(listOf("en", "it"), "cs"))
        assertEquals(null, ActionRow.nextLanguage(listOf("en"), "en"))
        assertEquals(null, ActionRow.notice(EditorAction.NEXT_LANGUAGE, state(languages = listOf("en"))))
    }

    @Test
    fun aShortcutSymbolInALetterListNamesItsAction() {
        // Every glyph is its own action's and no other's, and letters are no action.
        val plainText = setOf(
            EditorAction.SELECT_ALL, EditorAction.TOGGLE_NUMBER_ROW, EditorAction.EXPANDIFY,
            EditorAction.TOGGLE_TYPING_SPEED, EditorAction.TOGGLE_PECK_MODE,
            EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT, EditorAction.ARROW_RIGHT,
        )
        for (a in EditorAction.entries) {
            assertEquals(if (a in plainText) null else a, ActionRow.actionForGlyph(ActionRow.glyph(a)))
        }
        // `123`, `ALL` and the French quote `»` are text a list may hold, never an action.
        for (t in listOf("123", "ALL", "»", "wpm", "→", "←", "TAP")) assertEquals(null, ActionRow.actionForGlyph(t))
        assertEquals(EditorAction.entries.size, EditorAction.entries.map { ActionRow.glyph(it) }.toSet().size)
        for (t in listOf("a", "é", "1", "?", "", "all")) assertEquals(null, ActionRow.actionForGlyph(t))
    }

    @Test
    fun aSettingToggledFromTheKeyboardSaysTheStateItLeaves() {
        val off = ActionRow.KeyboardState(true, listOf("en"), "en", "full", null)
        assertEquals(ActionRow.Notice.Toggle(EditorAction.TOGGLE_NUMBER_ROW, true), ActionRow.notice(EditorAction.TOGGLE_NUMBER_ROW, off))
        val on = off.copy(nextWord = true, recentWords = true, tidySpaces = true)
        assertEquals(ActionRow.Notice.Toggle(EditorAction.TOGGLE_NEXT_WORD, false), ActionRow.notice(EditorAction.TOGGLE_NEXT_WORD, on))
        assertEquals(ActionRow.Notice.Toggle(EditorAction.TOGGLE_RECENT_WORDS, false), ActionRow.notice(EditorAction.TOGGLE_RECENT_WORDS, on))
        assertEquals(ActionRow.Notice.Toggle(EditorAction.TOGGLE_TIDY_SPACES, false), ActionRow.notice(EditorAction.TOGGLE_TIDY_SPACES, on))
    }

    @Test
    fun anActionWrittenByNameBecomesItsSymbol() {
        assertEquals("⎘", ActionRow.glyphForToken(":paste"))
        assertEquals(null, ActionRow.glyphForToken(":select_all"))
        assertEquals("↶", ActionRow.glyphForToken(":UNDO"))
        for (t in listOf("paste", ":", ":nothing", "a", "")) assertEquals(null, ActionRow.glyphForToken(t))
    }
}
