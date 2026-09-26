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
        // A row that could not offer an action would be a silent gap between the pickers
        // and this list, which is exactly the bug the edge-swipe editor had.
        assertEquals(EditorAction.entries.toSet(), ActionRow.ALL.toSet())
        assertEquals(ActionRow.ALL.size, ActionRow.ALL.distinct().size)
    }

    @Test
    fun everyGlyphIsShortAndDistinct() {
        // The ?123 popup draws a cell with no measuring and no clipping, so a long label
        // overlaps its neighbours rather than shrinking. Three characters is the widest
        // thing proven to work there.
        val glyphs = ActionRow.ALL.map { ActionRow.glyph(it) }
        for (g in glyphs) {
            assertTrue(g, g.isNotEmpty() && g.length <= 3)
        }
        assertEquals(glyphs.size, glyphs.distinct().size)
    }

    @Test
    fun theDefaultsAreTheFourAtTheFront() {
        // New actions append rather than rearrange, so turning one on does not move the
        // cells a user already knows the position of.
        assertEquals(ActionRow.DEFAULT, ActionRow.ALL.take(4).map { it.name }.toSet())
        for (name in ActionRow.DEFAULT) {
            assertTrue(name, ActionRow.ALL.any { it.name == name })
        }
    }

    @Test
    fun theRowIsCanonicallyOrderedWhateverOrderItIsChosenIn() {
        // The set carries membership and the order is imposed, exactly as the enabled
        // languages do it. A Set has no iteration order to trust.
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
        // A cell narrower than a thumb is not a shortcut. The bar holds the front of the
        // row rather than shrinking all of it.
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
}
