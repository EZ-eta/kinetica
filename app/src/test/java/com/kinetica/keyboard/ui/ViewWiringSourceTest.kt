package com.kinetica.keyboard.ui

import com.kinetica.keyboard.settings.PrefsXml
import com.kinetica.keyboard.settings.SettingsActivity
import com.kinetica.keyboard.settings.SettingsSynonyms
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * View wiring a JVM test cannot construct: a View needs a Context. Each guard is a source scan
 * of the one line that carries the behaviour.
 *
 * Read off disk, so a fail-first check here needs `--rerun-tasks`.
 */
class ViewWiringSourceTest {

    private fun source(file: String): String {
        val rel = "src/main/java/com/kinetica/keyboard/$file"
        val direct = Paths.get(rel)
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/$rel")
        assumeTrue("$file not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "$signature not found" }
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    /**
     * The long-press lists follow the enabled languages (LanguageAccents), so a language turned on
     * or off redraws the letters at once, not at the next field.
     */
    @Test
    fun aLanguageChangeRedrawsTheLongPressLists() {
        val ime = source("ime/KineticaIME.kt")
        assertTrue(
            ime.contains(
                "if (config.enabledLanguages != previous.enabledLanguages && keyboardView?.showsLetters == true) {\n" +
                    "                    keyboardView?.setKeyboardLayout(views.alphaLayout())",
            ),
        )
    }

    /**
     * A held shift swaps both slides' step (Prefs.SHIFT_SWAPS_SLIDES): the slot is taken on a shift

     * down with the switch on and freed at its lift or a cancel; a step under it spends the shift,
     * so no case menu opens and its lift does nothing; backspace fixes its mode at the first unit.
     */
    @Test
    fun aSlideUnderAHeldShiftSpendsIt() {
        val src = source("ui/KeyboardView.kt")
        assertTrue(src.contains("shiftSwapsSlides && key?.type == KeyType.SHIFT && routeByPointer[pid] == ROUTE_SPECIAL && shiftPointer == -1"))
        val spend = body(src, "private fun spendShift(")
        assertTrue(spend.contains("if (pid == pendingHoldPid) cancelHold()"))
        assertTrue(spend.contains("if (pid == popupPointer) dismissPopup()"))
        assertTrue(spend.contains("routeByPointer[pid] = ROUTE_NONE"))
        assertTrue(src.contains("        spendShift()\n        listener?.onCursorMove(dir, byWord)"))
        assertTrue(src.contains("if (units > 0) spendShift()"))
        assertTrue(body(src, "private fun handleUp(").contains("if (pid == shiftPointer) {\n            shiftPointer = -1\n            applySlideFlip()"))
        assertTrue(body(src, "private fun cancelActivePointers(").contains("shiftPointer = -1\n        applySlideFlip()"))
        val bs = body(source("keys/BackspaceController.kt"), "fun onMove(")
        assertTrue(bs.contains("everStaged = true\n                unit.fix()"))
        assertTrue(bs.contains("slideDpPerUnit(unit.chars"))
        assertTrue(source("ime/KeyboardViews.kt").contains("kv.shiftSwapsSlides = ime.config.shiftSwapsSlides"))
    }

    /**
     * The shortcut row the bar's button forced gives the words back at the next key down, which

     * every token and key passes through, and in a new field; and over words it owns the space.
     */
    @Test
    fun theForcedShortcutRowEndsWhenTypingResumes() {
        val ime = source("ime/KineticaIME.kt")
        assertTrue(body(ime, "override fun onKeyPressFeedback(").contains("suggestionBar?.endShortcuts()"))
        assertTrue(body(ime, "override fun onStartInputView(").contains("suggestionBar?.endShortcuts()"))
        val bar = source("ui/SuggestionBarView.kt")
        assertTrue(bar.contains("if (value) shortcutsForced = false"))
        val draw = body(bar, "private fun drawWords(")
        assertTrue(draw.indexOf("            return\n") in 0 until draw.indexOf("if (vis.isEmpty()) return"))
        assertTrue(bar.contains("downZone != ZONE_SHORTCUTS && !actionsVisible()"))
        assertTrue(source("ime/KeyboardViews.kt").contains("suggestionBar?.shortcutsButton = ime.config.shortcutsButton"))
    }

    /**
     * The editing page's held keys repeat (EditPage.repeats): armed on down at backspace's delay, a

     * lift after a repeat sends nothing, a cancel stops it.
     */
    @Test
    fun aHeldMoveOrDeleteRepeats() {
        val src = source("ui/KeyboardView.kt")
        assertTrue(src.contains("key?.type == KeyType.CHAR && EditPage.repeats(key.output) && repeatPointer == -1 -> {"))
        assertTrue(src.contains("keyRepeat.onDown(backspaceHoldArmMs)"))
        assertTrue(src.contains("if (!keyRepeat.onUp() && !repeatLeft) dispatchTap(pid, t)"))
        assertTrue(body(src, "private fun cancelActivePointers(").contains("keyRepeat.cancel()"))
    }

    /**
     * Select belongs to the editing page and the field: another board, a new field, or a copy, cut
     * or paste ends it; its key is drawn pressed while it is on; a move under it holds Shift.
     */
    @Test
    fun selectEndsOffThePageAndShowsOnItsKey() {
        val view = source("ui/KeyboardView.kt")
        assertTrue(body(view, "fun setKeyboardLayout(").contains("if (l.name != before) listener?.onBoardShown(l.name)"))
        assertTrue(view.contains("selectLit && key.output == EditorAction.SELECT_MODE.output -> pressedPaint"))
        val ime = source("ime/KineticaIME.kt")
        assertTrue(body(ime, "override fun onBoardShown(").contains("if (name != EditPage.NAME) commands.setSelecting(false)"))
        assertTrue(body(ime, "override fun onStartInput(").contains("commands.setSelecting(false)"))
        val commands = source("ime/EditorCommands.kt")
        assertTrue(body(commands, "fun setSelecting(").contains("ime.keyboardView?.selectLit = on"))
        val perform = body(commands, "fun performIfAction(")
        assertTrue(perform.contains("SelectMode.withShift(text)"))
        assertTrue(perform.contains("if (SelectMode.clearsAfter(action)) setSelecting(false)"))
        assertTrue(commands.contains("EditorAction.SELECT_MODE -> setSelecting(!selecting)"))
    }

    /** Every key sent with modifiers goes out as KeyCombo.strokes, so a shifted move selects. */
    @Test
    fun aKeySentWithShiftPressesShiftAsAKey() {
        val ich = source("ime/InputConnectionHelper.kt")
        assertTrue(body(ich, "fun sendKey(").contains("for (s in KeyCombo.strokes(keyCode, metaState)) {"))
        assertTrue(body(source("ime/EditorCommands.kt"), "fun sendKeyCombo(").contains("ime.ich.sendKey(combo.keyCode(), combo.metaState())"))
    }

    /** Undo and redo go out as keys only: the menu call as well would undo twice where both work. */
    @Test
    fun undoAndRedoAreSentAsKeysAlone() {
        val commands = source("ime/EditorCommands.kt")
        val perform = body(commands, "fun performIfAction(")
        assertTrue(perform.contains("SpecialKeys.comboFor(action)?.let { combo ->"))
        assertTrue(!commands.contains("android.R.id.undo") && !commands.contains("android.R.id.redo"))
    }

    /** The editing page opens and closes by one action, and a new field opens on the letters. */
    @Test
    fun theEditingPageOpensClosesAndLeavesWithTheField() {
        val commands = source("ime/EditorCommands.kt")
        assertTrue(commands.contains("EditorAction.EDIT_PAGE -> ime.toggleEditPage()"))
        val ime = source("ime/KineticaIME.kt")
        val toggle = body(ime, "internal fun toggleEditPage(")
        assertTrue(toggle.contains("if (keyboardView?.shownBoard == EditPage.NAME) {"))
        assertTrue(toggle.contains("keyboardView?.setKeyboardLayout(views.editPageLayout())"))
        assertTrue(body(ime, "override fun onStartInputView(").contains("|| keyboardView?.shownBoard == EditPage.NAME"))
        assertTrue(ime.contains("config.editPage != previous.editPage && keyboardView?.shownBoard == EditPage.NAME"))
    }

    /**
     * Each screen the keyboard asks settings to open is one settings opens, and a screen: a key left
     * out of OPENABLE_SCREENS opened settings at the top, as the symbol key's list editor did.
     */
    @Test
    fun everyScreenTheKeyboardOpensMayBeOpened() {
        val asked = Regex("""openSettings\(SettingsSynonyms\.(\w+)""")
        val names = listOf("ime/KineticaIME.kt", "ime/EditorCommands.kt")
            .flatMap { f -> asked.findAll(source(f)).map { it.groupValues[1] }.toList() }
            .distinct()
        assertTrue(names.containsAll(listOf("LONGPRESS_GROUP", "SYMBOL_PAGES_GROUP")))
        val screens = PrefsXml.walk(PrefsXml.read()).second
        for (n in names) {
            val key = SettingsSynonyms::class.java.getField(n).get(null) as String
            assertTrue("$n may be opened", key in SettingsActivity.OPENABLE_SCREENS)
            assertTrue("$n is a screen", key in screens)
        }
    }

    /**
     * A held letter remembers a swipe drawn beside it, from its down to its timer, and the timer
     * reads it: the other thumb usually lifts before the hold fires, so asking only at fire time
     * typed `@` in the middle of words.
     */
    @Test
    fun aHoldRemembersASwipeBesideIt() {
        val src = source("ui/KeyboardView.kt")
        val schedule = body(src, "private fun scheduleHold(")
        assertTrue("the hold's down does not ask", schedule.contains("holdBesideSwipe = engine?.otherSwipeCommitted(pid) == true"))
        val move = body(src, "private fun handleMove(")
        assertTrue("a swipe during the hold is not noted", move.contains("engine?.otherSwipeCommitted(pendingHoldPid) == true") && move.contains("holdBesideSwipe = true"))
        val fired = body(src, "private fun onHoldTimerFired(")
        assertTrue("the timer does not read it", fired.contains("swipeBeside = beside"))
    }

    /**
     * Every picked image failed with "could not be read": a bounds-only decode returns null by
     * design, and that null was taken for a stream that would not open.
     */
    @Test
    fun theImageSizeReadIsNotTakenForAFailedOpen() {
        val import = body(source("settings/BackdropStore.kt"), "fun import(")
        val decode = Regex("""decodeStream\(it, null, bounds\)[^\n]*\n[^\n]*""").find(import)?.value
        requireNotNull(decode) { "the bounds read is gone" }
        assertTrue("the bounds read's result is checked: $decode", "?:" !in decode)
    }

    /** Left plain, the bottom margin would show the window's colour, a band unlike the keys. */
    @Test
    fun theBottomGapTakesTheThemeBackground() {
        val applyTheme = body(source("ui/InputContainerView.kt"), "fun applyTheme(")
        assertTrue(
            "applyTheme leaves the bottom gap unpainted",
            applyTheme.contains("bottomGap.setBackgroundColor(theme.background)"),
        )
    }

    /** Without the wake in the frame loop, the lit keys freeze at full brightness after a lift. */
    @Test
    fun theFrameLoopRunsWhileKeysAreLit() {
        val src = source("ui/KeyboardView.kt")
        val frame = src.substring(src.indexOf("override fun doFrame("), src.indexOf("invalidate()", src.indexOf("override fun doFrame(")))
        assertTrue("doFrame stops before the lit keys fade", frame.contains("keyWake.anyLit(now)"))
    }

    /** The search panel takes the bar's place and leaves the keys up: they type the query. */
    @Test
    fun theEmojiSearchHidesTheBarNotTheKeys() {
        val show = body(source("ui/InputContainerView.kt"), "fun showEmojiSearch(")
        assertTrue("the panel goes where the bar is", show.contains("indexOfChild(suggestionBar)"))
        assertTrue("the bar steps aside", show.contains("suggestionBar.visibility = GONE"))
        assertTrue("the keys must stay", !show.contains("keyboardView.visibility"))
    }

    /** The pane takes the bar's place too, at the search panel's height: more emoji a page. */
    @Test
    fun theEmojiPaneCoversTheBar() {
        val show = body(source("ui/InputContainerView.kt"), "fun showEmojiPicker(")
        assertTrue("the bar stays over the pane", show.contains("suggestionBar.visibility = GONE"))
        assertTrue("the pane is the keys' height only", show.contains("keyboardView.layoutParams.height + maxOf(topPx"))
        val hide = body(source("ui/InputContainerView.kt"), "fun hideEmojiPicker(")
        assertTrue("the bar comes back over the search panel", hide.contains("if (picker?.visibility == VISIBLE) suggestionBar.visibility = VISIBLE"))
        val open = body(source("ime/KeyboardViews.kt"), "fun openEmojiPicker(")
        assertTrue(open.contains("showEmojiPicker(picker, dpToPx(KeyboardHeights.emojiSearchDp("))
    }

        /** A hidden trail still steps its hue, or rainbow lit keys stay one colour. */
    @Test
    fun theHueStepsWhenOnlyKeysLight() {
        val transition = body(source("ui/KeyboardView.kt"), "fun onEngineKeyTransition(")
        val bump = transition.indexOf("trailRenderer.bumpHue(streamId)")
        assertTrue("no hue step", bump >= 0)
        assertTrue("the hue steps only with the trail drawn", bump < transition.indexOf("swipeLook.drawsTrail"))
    }

    /**
     * A symbol's own long-press list reaches its key on both pages, an action written by name runs,
     * an edit shows on a page that is up, and the held menu's edit jump opens the symbol's row.
     */
    @Test
    fun aSymbolsListReachesItsKey() {
        val page = body(source("ime/KeyboardViews.kt"), "fun symbolLayout(")
        assertTrue("the lists laid over the page", page.contains("SymbolRows.withLists(rows, lists)"))
        assertTrue("an action written by name runs", page.contains("list.map(AlphaLayouts::cell)"))
        assertTrue("an edit shows at once", source("ime/KineticaIME.kt").contains("config.symbolAlternates != previous.symbolAlternates"))
        val kv = source("ui/KeyboardView.kt")
        assertTrue("the edit jump knows a symbol", kv.contains("val symbolList = SymbolRows.carriesList(layout?.name, key)"))
        assertTrue("and opens it", kv.contains("if (symbol != null) listener?.onEditSymbolAlternates(symbol)"))
    }

    /** Every page's keys are placed in the arrangement its name allows: split mode leaves the numpad whole. */
    @Test
    fun keysArePlacedInTheirPagesOwnArrangement() {
        val rebuild = body(source("ui/KeyboardView.kt"), "private fun rebuild(")
        assertTrue("the page's own mode", rebuild.contains("val mode = LayoutTransforms.modeFor(layoutMode, l.name)"))
        assertTrue("keys placed in it", rebuild.contains("LayoutTransforms.apply(mode, k, w, h, sidePad)"))
    }

    /** Both the bar's words and its recent columns are shortened by one rule, which follows the switch. */
    @Test
    fun barWordsAreShortenedThroughTheMiddleCutSwitch() {
        val fit = body(source("ui/SuggestionBarView.kt"), "private fun fit(")
        assertTrue("the bar's fit goes through BarFit", fit.contains("BarFit.fit(word, maxW - inset, middleCut)"))
        assertTrue("the switch reaches the bar", source("ime/KeyboardViews.kt").contains("middleCut = ime.config.barMiddleCut"))
    }

    /**
     * A touch on no key goes to the nearest letter: only when the view has no key there, so a
     * shift or a comma is never taken, and the letter's key gets the press as a tap on it would.
     */
    @Test
    fun aTouchOnNoKeyIsSnappedToTheNearestLetter() {
        val down = body(source("ui/KeyboardView.kt"), "private fun handleDown(")
        assertTrue("snapped over a key of the view's own", down.contains("val snap = hit == -1 && engineActive"))
        assertTrue("the press is not the letter's", down.contains("engine?.letterNear(x, y)?.let { keyOfCode.getOrElse(it) { -1 } }"))
        assertTrue("the engine is not told", down.contains("engine?.onPointerDown(pid, x, y, t, snap) == true"))
    }

    /** A word's mark is set by the switch, drawn left of it, and its width is reserved. */
    @Test
    fun barWordsAreMarkedThroughTheSwitch() {
        val bar = source("ui/SuggestionBarView.kt")
        val draw = body(bar, "private fun drawWords(")
        assertTrue("the mark is not drawn", draw.contains("s.mark.glyph?.let"))
        assertTrue("the word does not leave room for it", draw.contains("fit(s.word, paint, zoneW - markReach(s.mark, paint, orn))"))
        assertTrue("the packing does not reserve it", body(bar, "private fun zoneWidths(").contains("markReach(it.mark, primaryPaint, orn)"))
        val build = body(source("ime/BarContents.kt"), "fun barSuggestion(")
        assertTrue("the switch does not reach the bar", build.contains("mark = if (ime.config.barWordMarks) markOf(display) else BarMark.NONE"))
    }

    /** With the switch, bold follows the word space writes, and the letters ride second. */
    @Test
    fun theBarBoldsTheWordSpaceWritesThroughTheSwitch() {
        val draw = body(source("ui/SuggestionBarView.kt"), "private fun drawWords(")
        assertTrue("bold is drawn where it is set", draw.contains("fullIdx == besideZones + boldAt"))
        val push = body(source("ime/BarContents.kt"), "fun pushSuggestions(")
        assertTrue("the letters follow the switch", push.contains("literalSecond = spaceBold"))
        assertTrue("bold follows the switch", push.contains("val bold = if (spaceBold) boldIndex(words, spaceWord()) else 0"))
        assertTrue(push.contains("setSuggestions(words.map { barSuggestion(it) }, bold)"))
        val commit = body(source("ime/CommitPath.kt"), "fun finalizePendingWord(")
        assertTrue("the commit and the bold share one gate", commit.contains("tapCommitMayCorrect(") && source("ime/BarContents.kt").contains("spaceCommitWord("))
    }

    /**
     * The action icons replace the symbols where an action is drawn; the symbol is the fallback.
     * The `?123` menu's cells are all actions, a long-press list's are text unless marked.
     */
    @Test
    fun actionsDrawTheirIconsBeforeTheirSymbols() {
        val bar = body(source("ui/SuggestionBarView.kt"), "private fun drawWords(")
        assertTrue("the bar draws symbols only", bar.contains("icons.draw(canvas, GlyphIcons.actionOfSymbol(acts[i])"))
        val kv = source("ui/KeyboardView.kt")
        val popup = body(kv, "private fun drawPopup(")
        assertTrue("a popup draws symbols only", popup.contains("icons.draw(canvas, p.actionOf(p.cells[i])"))
        val grid = body(kv, "private fun drawPopupGrid(")
        assertTrue("a grid draws symbols only", grid.contains("icons.draw(canvas, p.actionOf(p.cells[i])"))
        assertTrue("the ?123 menu reads its cells as text", kv.contains("overIdx = space, actionCells = true,"))
        val chords = body(kv, "private fun drawChordLabels(")
        assertTrue("chord labels draw symbols only", chords.contains("val action = ActionRow.actionForGlyph(s)"))
        val key = body(kv, "private fun drawKey(")
        assertTrue("an action key draws its symbol only", key.contains("val keyAction = EditorAction.of(key.output)"))
    }
}
