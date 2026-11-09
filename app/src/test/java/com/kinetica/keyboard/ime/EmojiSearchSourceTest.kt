package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emoji search's wiring into the key paths, which needs a live editor: each guard is a scan
 * for the check that keeps a search key out of the field. Read off disk, so a fail-first check
 * here needs `--rerun-tasks`.
 */
class EmojiSearchSourceTest {

    private val ime by lazy { ImeSources.declaring("class KineticaIME").text }
    private val mode by lazy { ImeSources.declaring("internal class EmojiSearchMode").text }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "$signature not found" }
        val end = src.indexOf("\n    }\n", start).let { if (it < 0) src.indexOf("\n        }\n", start) else it }
        return src.substring(start, end)
    }

    /** [guard] comes before every one of [after] in [text]. */
    private fun before(text: String, guard: String, vararg after: String) {
        val g = text.indexOf(guard)
        assertTrue("no `$guard`", g >= 0)
        for (a in after) {
            val i = text.indexOf(a)
            assertTrue("`$a` missing", i >= 0)
            assertTrue("`$guard` must come before `$a`", g < i)
        }
    }

    @Test
    fun aGestureWhileSearchingNeverReachesTheField() {
        val token = body(ime, "override fun onTokenFinalized(")
        before(token, "if (emojiSearch.active)", "commitTracked(", "comp?.onToken(token)", "barContents.clearCorrection()")
    }

    @Test
    fun aSwipesDecodeWhileSearchingIsTheQuerys() {
        val decoded = body(ime, "override fun onCandidates(")
        before(decoded, "if (emojiSearch.active)", "edits.retypeRejected", "onCandidatesRanked(")
    }

    /**
     * A composer cleared on every token decoded each swipe alone, so a two-thumb `heart` came out
     * `h eat`. Taps and swipes join one word; only a word end clears it.
     */
    @Test
    fun everyGestureJoinsOneWordInTheSearch() {
        val token = body(mode, "fun onToken(")
        assertTrue("the composer is cleared per token", !token.contains("composer?.clear()"))
        assertTrue("a tap stays out of the composer", token.contains("comp?.onToken(token)"))
        val ended = body(mode, "private fun endWordInHand(")
        assertTrue(ended.contains("ime.composer?.clear()"))
        for (f in listOf("fun space(", "fun delete(", "private fun type(")) {
            assertTrue("$f leaves the word in the composer", body(mode, f).contains("endWordInHand()"))
        }
    }

    /** A swiped word ends on the field's swipe pause, and any new touch holds it open. */
    @Test
    fun aSwipedWordEndsOnTheAutospacePause() {
        val arm = body(mode, "private fun armWordEnd(")
        assertTrue(arm.contains("autospaceWait(ime.config.autospaceDelayMs, comp.lastTokenEnd"))
        assertTrue(arm.contains("ime.engine.hasActivePointers()"))
        assertTrue(body(mode, "fun onDecoded(").contains("armWordEnd()"))
        assertTrue(body(ime, "override fun onKeyPressFeedback(").contains("emojiSearch.cancelWordEnd()"))
        assertTrue(body(mode, "fun onToken(").contains("cancelWordEnd()"))
    }

    @Test
    fun keysAndBackspaceWhileSearchingEditTheQuery() {
        val tap = body(ime, "override fun onKeyTap(")
        before(tap, "if (emojiSearch.onKeyTap(key)) return", "onSpace()", "backspace.onKey(tap = true)", "onEnter()")
        val del = body(ime, "override fun onDeleteChar(")
        before(del, "emojiSearch.active", "backspace.onKey(tap)")
        val stage = body(ime, "override fun onStageDelete(")
        before(stage, "!emojiSearch.active", "backspace.stage(")
        val staged = body(ime, "override fun onCommitStagedDelete(")
        before(staged, "emojiSearch.active", "backspace.commitStaged()")
        val alternate = body(ime, "override fun onKeyAlternate(")
        before(alternate, "if (emojiSearch.onAlternate(text)) return", "composeAccentedLetter(", "edits.recaseWordInHand(")
        val cursor = body(ime, "override fun onCursorMove(")
        before(cursor, "if (emojiSearch.active) return", "sendDownUpKeyEvents(")
    }

    @Test
    fun anEmptyQuerysBackspaceDeletesNothing() {
        // Backspace.onKey must never run from the search: an empty query is a no-op.
        val delete = body(mode, "fun delete(")
        assertTrue(delete.contains("query.backspace()"))
        assertTrue("the field's backspace runs from the search", !mode.contains("backspace.onKey"))
    }

    @Test
    fun aNewFieldOrANewViewClosesTheSearch() {
        assertTrue(body(ime, "override fun onStartInputView(").contains("emojiSearch.close(\"field\")"))
        assertTrue(body(ime, "override fun onCreateInputView(").contains("emojiSearch.reset()"))
        // A swipe still decoding when the panel closes must not land in the field.
        assertTrue(body(mode, "fun close(").contains("ime.composer?.clear()"))
    }

    @Test
    fun aPickCountsForTheFrequentlyUsedTab() {
        val pick = body(mode, "private fun pick(")
        before(pick, "ime.learner.recordEmojiUse(ch)", "ime.commitTracked(ch)")
    }

    @Test
    fun theActionTogglesThePanel() {
        val commands = ImeSources.declaring("private fun performLocalAction(").text
        assertTrue(commands.contains("EditorAction.EMOJI_SEARCH -> ime.emojiSearch.toggle(\"action\")"))
    }

    @Test
    fun anEmptyQueryFillsTheStripPastTheRecents() {
        // A strip with no picks behind it showed nothing at all.
        val refresh = body(mode, "private fun refresh()")
        assertTrue(refresh.contains("EmojiRecents.strip(recents, assets.catalog.entries"))
    }
}
