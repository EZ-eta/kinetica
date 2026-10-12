package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What enter does, read off the field the way Android defines it. */
class EnterBehaviorTest {

    private val newline = EnterBehavior.Result.Newline

    private fun field(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = EditorInfo.IME_ACTION_NONE,
        label: String? = null,
        actionId: Int = 0,
    ): EditorState = EditorState.from(
        EditorInfo().also {
            it.inputType = inputType
            it.imeOptions = imeOptions
            it.actionLabel = label
            it.actionId = actionId
        },
    )

    private val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test
    fun aMultiLineSearchBoxSearches() {
        // Google's search box: multi-line, asks for Search, no NO_ENTER_ACTION. It once got a
        // newline.
        val s = field(multiLine, EditorInfo.IME_ACTION_SEARCH)
        assertEquals(EnterBehavior.Result.Action(EditorInfo.IME_ACTION_SEARCH), EnterBehavior.resolve(s, true))
        assertEquals(EnterBehavior.Label.SEARCH, EnterBehavior.labelKind(s, true))
    }

    @Test
    fun theFlagAsksForANewline() {
        // TextView sets it on every multi-line field by itself: a chat box with Send keeps its newline.
        val s = field(multiLine, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertEquals(newline, EnterBehavior.resolve(s, true))
        assertNull(EnterBehavior.labelKind(s, true))
    }

    @Test
    fun aSingleLineSendSends() {
        val s = field(imeOptions = EditorInfo.IME_ACTION_SEND)
        assertEquals(EnterBehavior.Result.Action(EditorInfo.IME_ACTION_SEND), EnterBehavior.resolve(s, true))
        assertEquals(EnterBehavior.Label.SEND, EnterBehavior.labelKind(s, true))
    }

    @Test
    fun actionNoneIsANewline() {
        assertEquals(newline, EnterBehavior.resolve(field(imeOptions = EditorInfo.IME_ACTION_NONE), true))
    }

    @Test
    fun anUnspecifiedActionStillReachesTheApp() {
        // As in AOSP: the framework turns it into an enter key event.
        val s = field(imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals(EnterBehavior.Result.Action(EditorInfo.IME_ACTION_UNSPECIFIED), EnterBehavior.resolve(s, true))
        assertNull(EnterBehavior.labelKind(s, true))
    }

    @Test
    fun anAppsOwnLabelRunsItsOwnId() {
        val s = field(imeOptions = EditorInfo.IME_ACTION_NONE, label = "Post", actionId = 42)
        assertEquals(EnterBehavior.Result.Action(42), EnterBehavior.resolve(s, true))
        assertEquals(EnterBehavior.Label.Custom("Post"), EnterBehavior.labelKind(s, true))
    }

    @Test
    fun theSettingOffIsTheOldRule() {
        assertEquals(newline, EnterBehavior.resolve(field(multiLine, EditorInfo.IME_ACTION_SEARCH), false))
        assertEquals(newline, EnterBehavior.resolve(field(imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED), false))
        assertEquals(
            EnterBehavior.Result.Action(EditorInfo.IME_ACTION_GO),
            EnterBehavior.resolve(field(imeOptions = EditorInfo.IME_ACTION_GO), false),
        )
        assertNull(EnterBehavior.labelKind(field(multiLine, EditorInfo.IME_ACTION_SEARCH), false))
    }

    @Test
    fun holdToSendGivesTheTapTheNewline() {
        val send = EnterBehavior.Result.Action(EditorInfo.IME_ACTION_SEND)
        val s = field(multiLine, EditorInfo.IME_ACTION_SEND)
        assertEquals(newline, EnterBehavior.tap(s, followApp = true, holdSends = true))
        assertEquals(send, EnterBehavior.holdAction(s, holdSends = true))
        // The key shows the plain glyph: the tap writes a newline.
        assertNull(EnterBehavior.labelKind(s, true, holdSends = true))
    }

    @Test
    fun aChatBoxThatAsksForANewlineStillSendsOnTheHold() {
        // The flag asks enter for a newline; the tap gives it, and the hold reaches Send.
        val s = field(multiLine, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertEquals(newline, EnterBehavior.tap(s, followApp = true, holdSends = true))
        assertEquals(EnterBehavior.Result.Action(EditorInfo.IME_ACTION_SEND), EnterBehavior.holdAction(s, true))
    }

    @Test
    fun aSingleLineFieldKeepsItsActionOnTheTap() {
        // A search box or a login field has no use for a newline: its tap still searches.
        val s = field(imeOptions = EditorInfo.IME_ACTION_SEARCH)
        assertNull(EnterBehavior.holdAction(s, holdSends = true))
        assertEquals(EnterBehavior.Result.Action(EditorInfo.IME_ACTION_SEARCH), EnterBehavior.tap(s, true, holdSends = true))
    }

    @Test
    fun aFieldWithNoActionKeepsItsEnter() {
        val s = field(imeOptions = EditorInfo.IME_ACTION_NONE)
        assertNull(EnterBehavior.holdAction(s, holdSends = true))
        assertEquals(EnterBehavior.resolve(s, true), EnterBehavior.tap(s, followApp = true, holdSends = true))
    }

    @Test
    fun theSettingOffChangesNothing() {
        val s = field(imeOptions = EditorInfo.IME_ACTION_SEARCH)
        assertNull(EnterBehavior.holdAction(s, holdSends = false))
        assertEquals(EnterBehavior.resolve(s, true), EnterBehavior.tap(s, followApp = true, holdSends = false))
    }
}
