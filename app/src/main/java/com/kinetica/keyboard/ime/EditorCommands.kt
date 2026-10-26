package com.kinetica.keyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.keys.KeyCombo
import com.kinetica.keyboard.keys.SpecialKeys
import com.kinetica.keyboard.keys.WordCase
import com.kinetica.keyboard.settings.ActionLabels
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.SettingsActivity

/**
 * The commands a key, a chord, an edge swipe, an expansion or a shortcut row can run: editor actions
 * sent to the app, the ones the keyboard carries out itself, the one-shot Ctrl, the setting toggles,
 * and the spacebar notice that says what a shortcut did.
 */
internal class EditorCommands(private val ime: KineticaIME) {

    // The shortcut rows, resolved once per config change, so the index a surface reports and
    // the row it drew cannot disagree.
    var barActions: List<EditorAction> = emptyList()

    var menuActions: List<EditorAction> = emptyList()

    // The one-shot Ctrl: set by CTRL_NEXT, spent by the next key.
    var ctrlPending = false

    // Clears the spacebar's shortcut notice; the label has no timer of its own. See
    // showSpacebarNotice.
    private val spacebarNoticeRunnable = Runnable { ime.keyboardView?.spacebarNotice = null }

    /**
     * Runs [text] as an editor command when it names one, and reports whether it did. Every
     * surface that can hold a command goes through here, so none inserts "action:paste" as text.
     *
     * The pending word is settled first so the command applies to finished content. A string
     * with the reserved prefix but no command is swallowed: it is a typo in an expansion, and
     * typing it is the worse answer. RETYPE runs before the settling, since the pending word
     * is what it aims at.
     */
    fun performIfAction(text: String): Boolean {
        // A key combination is a target too, wherever an action name is.
        KeyCombo.parse(text)?.let {
            sendKeyCombo(it)
            return true
        }
        val action = EditorAction.of(text)
        if (action == null) return EditorAction.isUnknownAction(text)
        // Undo and redo are context-menu commands, so the app does the work and the keyboard
        // keeps no history. performContextMenuAction reports delivery, not effect, so an editor
        // without an undo stack is a silent no-op, the same contract as paste.
        val menuId = when (action) {
            EditorAction.PASTE -> android.R.id.paste
            EditorAction.COPY -> android.R.id.copy
            EditorAction.CUT -> android.R.id.cut
            EditorAction.SELECT_ALL -> android.R.id.selectAll
            EditorAction.UNDO -> android.R.id.undo
            EditorAction.REDO -> android.R.id.redo
            EditorAction.RETYPE, EditorAction.EXPANDIFY, EditorAction.SETTINGS,
            EditorAction.NEXT_LANGUAGE, EditorAction.TOGGLE_AUTOSPACE,
            EditorAction.ONE_HANDED, EditorAction.DATE, EditorAction.TIME,
            EditorAction.ENTER, EditorAction.COPY_LINE, EditorAction.CLEAN_LINKS,
            EditorAction.TOGGLE_NEXT_WORD, EditorAction.TOGGLE_RECENT_WORDS, EditorAction.TOGGLE_NUMBER_ROW,
            EditorAction.TOGGLE_TIDY_SPACES, EditorAction.TOGGLE_TYPING_SPEED,
            EditorAction.TOGGLE_PECK_MODE,
            EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
            EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
            EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
            EditorAction.BACKSPACE, EditorAction.CTRL_NEXT,
            EditorAction.CASE_LOWER, EditorAction.CASE_TITLE, EditorAction.CASE_UPPER,
            -> null
        }
        if (menuId == null) {
            performLocalAction(action)
            return true
        }
        if (action == EditorAction.PASTE && pasteCleaned()) return true
        ime.commit.finalizePendingWord()
        ime.ich.performContextMenuAction(menuId)
        ime.selectionLedger.expectAny(SystemClock.uptimeMillis())
        ime.forgetCommitMarks()
        return true
    }

    /**
     * The actions the keyboard carries out itself instead of asking the app.
     *
     * RETYPE owns the pending word, so it runs before the word is settled; EXPANDIFY reads
     * what the editor holds, so it settles first. The toggles change the keyboard, not the
     * text: each is a preference write, and the listener rebuilds the config.
     */
    private fun performLocalAction(action: EditorAction) {
        when (action) {
            EditorAction.RETYPE -> ime.edits.retypeCurrentWord()
            EditorAction.EXPANDIFY -> ime.expansions.expandifyAtCursor()
            EditorAction.SETTINGS -> openSettings()
            EditorAction.NEXT_LANGUAGE -> cycleLanguage()
            EditorAction.TOGGLE_AUTOSPACE -> toggleAutospace()
            EditorAction.ONE_HANDED -> toggleOneHanded()
            EditorAction.DATE -> insertNow(date = true)
            EditorAction.TIME -> insertNow(date = false)
            EditorAction.ENTER -> ime.onEnter()
            EditorAction.COPY_LINE -> copyLine()
            EditorAction.CLEAN_LINKS -> cleanLinks()
            EditorAction.TOGGLE_NEXT_WORD -> togglePref(Prefs.NEXT_WORD, !ime.config.nextWord)
            EditorAction.TOGGLE_RECENT_WORDS -> togglePref(Prefs.RECENT_WORDS, !ime.config.recentWords)
            EditorAction.TOGGLE_NUMBER_ROW -> togglePref(Prefs.NUMBER_ROW, !ime.config.numberRow)
            EditorAction.TOGGLE_TIDY_SPACES -> togglePref(Prefs.TIDY_SPACES, !ime.config.tidySpaces)
            EditorAction.TOGGLE_TYPING_SPEED -> togglePref(Prefs.TYPING_SPEED, !ime.config.typingSpeed)
            EditorAction.TOGGLE_PECK_MODE -> togglePeckMode()
            EditorAction.TAB, EditorAction.ESCAPE, EditorAction.FORWARD_DELETE, EditorAction.HOME,
            EditorAction.END, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.ARROW_LEFT,
            EditorAction.ARROW_RIGHT, EditorAction.PAGE_UP, EditorAction.PAGE_DOWN,
            -> sendSpecialKey(action)
            EditorAction.BACKSPACE -> ime.backspace.onKey()
            EditorAction.CTRL_NEXT -> {
                ctrlPending = !ctrlPending
                DecodeTrace.log { "  ctrl pending=$ctrlPending" }
            }
            // The word in hand, not after it: like RETYPE, it runs before anything is settled.
            EditorAction.CASE_LOWER -> ime.edits.recaseWordInHand(WordCase.LOWER)
            EditorAction.CASE_TITLE -> ime.edits.recaseWordInHand(WordCase.TITLE)
            EditorAction.CASE_UPPER -> ime.edits.recaseWordInHand(WordCase.UPPER)
            // Every context-menu action is handled by the caller.
            else -> Unit
        }
    }

    /** A key with modifiers, as a hardware keyboard sends it, once the word is settled. */
    fun sendKeyCombo(combo: KeyCombo) {
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        DecodeTrace.log { "  combo sent keys=${combo.encode()}" }
        ime.ich.sendKey(combo.keyCode(), combo.metaState())
        ime.updateAutoShift()
    }

    /**
     * The one-shot Ctrl: when it is held, [key] goes out as Ctrl+key instead of being typed.
     * True when it went, false when Ctrl was not held or the key has no code to send.
     */
    fun consumeCtrl(key: String): Boolean {
        if (!ctrlPending) return false
        ctrlPending = false
        val combo = KeyCombo.ctrlOf(key)
        if (combo == null) {
            DecodeTrace.log { "  ctrl dropped key=$key" }
            return false
        }
        sendKeyCombo(combo)
        return true
    }

    /**
     * A key the keyboard has no key for, as a key event, after the word is settled so the event
     * lands behind it. A cursor the event moves reaches onUpdateSelection as the user's own move.
     */
    private fun sendSpecialKey(action: EditorAction) {
        SpecialKeys.comboKeyFor(action)?.let { if (consumeCtrl(it)) return }
        val code = SpecialKeys.keyCodeFor(action) ?: return
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        DecodeTrace.log { "  special key action=${action.name}" }
        ime.sendDownUpKeyEvents(code)
        ime.updateAutoShift()
    }

    /**
     * Today's date or the time, in the format the phone is set to, so a 24-hour phone
     * gets 24-hour time without a setting of our own.
     */
    private fun insertNow(date: Boolean) {
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        val now = java.util.Date()
        val text = if (date) {
            android.text.format.DateFormat.getDateFormat(ime).format(now)
        } else {
            android.text.format.DateFormat.getTimeFormat(ime).format(now)
        }
        ime.commitTracked(text)
        ime.updateAutoShift()
    }

    /**
     * The line the cursor is on, onto the clipboard (#19). Read out of the editor in this
     * call and handed to the clipboard, so nothing is selected and no remembered offset is
     * trusted. A line longer than the read is refused, not half copied, and a private field
     * is never read for it.
     */
    private fun copyLine() {
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        if (ime.editorState.privateMode) return
        val before = ime.ich.textBeforeCursor(COPY_LINE_READ_CHARS) ?: return
        val after = ime.ich.textAfterCursor(COPY_LINE_READ_CHARS) ?: return
        val selected = if (ime.selectionLength() > 0) ime.ich.selectedText() ?: return else ""
        val line = lineAroundCursor(before, selected, after, COPY_LINE_READ_CHARS)
        DecodeTrace.log { "  copy line len=${line?.length ?: "refused"}" }
        if (line.isNullOrBlank()) {
            ime.views.vibrateForKeyPress()
            return
        }
        ime.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(ime.getString(R.string.clip_label_line), line))
    }

    /**
     * Tracking parameters out of the selection's links, or out of the link just before the
     * cursor, as after a paste (#26). Nothing to clean buzzes, as a refused line copy does. A
     * private field is never read for it.
     */
    private fun cleanLinks() {
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        if (ime.editorState.privateMode) return
        if (ime.selectionLength() > 0) {
            val text = ime.ich.selectedText()?.toString() ?: return
            val cleaned = if (text.length > CLEAN_LINKS_READ_CHARS) text else LinkCleaner.clean(text)
            DecodeTrace.log { "  clean links src=selection len=${text.length}->${cleaned.length}" }
            if (cleaned == text) {
                ime.views.vibrateForKeyPress()
                return
            }
            val at = minOf(ime.selStart, ime.selEnd)
            ime.ich.replaceSelection(at, cleaned)
            // The length changes, unlike a re-case: the words recorded after it move.
            ime.commitHistory.onEdit(at + text.length, text.length, cleaned.length)
            ime.expectAt(at, at + cleaned.length)
            ime.forgetCommitMarks()
            return
        }
        val before = ime.ich.textBeforeCursor(CLEAN_LINKS_READ_CHARS) ?: return
        val found = LinkCleaner.cleanLastLink(before)
        DecodeTrace.log { "  clean links src=cursor changed=${found != null}" }
        if (found == null) {
            ime.views.vibrateForKeyPress()
            return
        }
        ime.ich.replaceBeforeCursor(found.span, found.text)
        ime.expectAfter(found.span, found.text.length)
        ime.updateAutoShift()
    }

    /**
     * The clipboard's text with its links cleaned, written by the keyboard, when the setting
     * asks and a link changes. False leaves the paste to the app, which keeps any formatting:
     * only the keyboard's own paste can be cleaned, the app's menu never comes here.
     *
     * One plain-text item only. The app joins several items, and a URI item would be read on
     * the main thread, so both are left to the app's paste.
     */
    private fun pasteCleaned(): Boolean {
        if (!ime.config.cleanLinksOnPaste || ime.editorState.privateMode) return false
        val clip = ime.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return false
        if (clip.itemCount != 1) return false
        val text = clip.getItemAt(0).text?.toString() ?: return false
        val cleaned = LinkCleaner.clean(text)
        if (cleaned == text) return false
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        val replacing = ime.selectionLength() > 0
        ime.ich.commitText(cleaned)
        // A replaced selection moves the cursor by its own length too, as the app's paste does.
        if (replacing) ime.selectionLedger.expectAny(SystemClock.uptimeMillis()) else ime.expectAfter(0, cleaned.length)
        if (replacing) ime.forgetCommitMarks()
        ime.autospace.inserted = false
        ime.autospace.fromTaps = false
        DecodeTrace.log { "  paste cleaned len=${text.length}->${cleaned.length}" }
        ime.updateAutoShift()
        return true
    }

    /** Opens settings, at [screen] with [reveal] scrolled to and flashed when given. */
    fun openSettings(screen: String? = null, reveal: String? = null) {
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        ime.requestHideSelf(0)
        ime.startActivity(
            Intent(ime, SettingsActivity::class.java).apply {
                // A service context has no activity task to attach to.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (screen != null) putExtra(SettingsActivity.EXTRA_SCREEN, screen)
                if (reveal != null) putExtra(SettingsActivity.EXTRA_REVEAL, reveal)
            },
        )
    }

    /** A shortcut chosen from the suggestion bar's row, by its index into [barActions]. */
    fun performBarAction(index: Int) = performShortcut(barActions.getOrNull(index))

    /** A shortcut chosen from the ?123 hold menu, by its index into [menuActions]. */
    fun performMenuAction(index: Int) = performShortcut(menuActions.getOrNull(index))

    fun performShortcut(action: EditorAction?) {
        if (action == null) return
        // Decided before the action runs: the toggles write a preference, and the notice
        // says what that write leaves behind.
        val notice = ActionRow.notice(action, shortcutState())
        // No buzz here: the actions that change the keyboard buzz themselves, and the editor
        // commands are silent on every other route, so paste feels the same from the row.
        performIfAction(action.output)
        notice?.let { noticeText(it) }?.let { showSpacebarNotice(it) }
    }

    private fun shortcutState(): ActionRow.KeyboardState {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ime)
        return ActionRow.KeyboardState(
            autospace = ime.config.autospace,
            languages = ime.config.enabledLanguages,
            language = ime.config.language,
            layoutMode = prefs.getString(Prefs.LAYOUT_MODE, Prefs.DEFAULT_LAYOUT_MODE)
                ?: Prefs.DEFAULT_LAYOUT_MODE,
            rememberedOneHanded = prefs.getString(Prefs.LAYOUT_MODE_ONE_HANDED, null),
            nextWord = ime.config.nextWord,
            recentWords = ime.config.recentWords,
            numberRow = ime.config.numberRow,
            tidySpaces = ime.config.tidySpaces,
            typingSpeed = ime.config.typingSpeed,
            peckMode = ime.config.peckMode,
            ctrlPending = ctrlPending,
        )
    }

    private fun noticeText(notice: ActionRow.Notice): String? = when (notice) {
        is ActionRow.Notice.Sent -> ActionLabels.noticeRes(notice.action)?.let { ime.getString(it) }
        is ActionRow.Notice.Autospace -> ime.getString(
            if (notice.on) R.string.notice_autospace_on else R.string.notice_autospace_off,
        )
        is ActionRow.Notice.Toggle -> ActionLabels.toggleNameRes(notice.action)?.let {
            ime.getString(if (notice.on) R.string.notice_toggle_on else R.string.notice_toggle_off, ime.getString(it))
        }
        is ActionRow.Notice.Language ->
            entryFor(R.array.language_values, R.array.language_entries, notice.code)
        is ActionRow.Notice.Layout ->
            entryFor(R.array.layout_mode_values, R.array.layout_mode_entries, notice.mode)
    }

    /** The settings list's own wording for [value], so the spacebar and the chooser agree. */
    private fun entryFor(valuesRes: Int, entriesRes: Int, value: String): String {
        val values = ime.resources.getStringArray(valuesRes)
        val entries = ime.resources.getStringArray(entriesRes)
        return entries.getOrNull(values.indexOf(value)) ?: value
    }

    /**
     * Shows [text] on the spacebar for [SPACEBAR_NOTICE_MS]. A second notice replaces the
     * first and restarts the clock, so two quick taps never leave a stale one behind.
     */
    private fun showSpacebarNotice(text: String) {
        ime.mainHandler.removeCallbacks(spacebarNoticeRunnable)
        ime.keyboardView?.spacebarNotice = text
        ime.mainHandler.postDelayed(spacebarNoticeRunnable, SPACEBAR_NOTICE_MS)
    }

    fun clearSpacebarNotice() {
        ime.mainHandler.removeCallbacks(spacebarNoticeRunnable)
        ime.keyboardView?.spacebarNotice = null
    }

    /** A boolean setting written from the keyboard; the preference listener does the rest. */
    private fun togglePref(key: String, value: Boolean) {
        ime.views.vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(ime).edit().putBoolean(key, value).apply()
    }

    /** Autospace on or off for good. The spacebar dot follows through applyViewConfig. */
    private fun toggleAutospace() {
        ime.views.vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(ime)
            .edit().putBoolean(Prefs.AUTOSPACE, !ime.config.autospace).apply()
    }

    /**
     * One-handed on or off, remembering which side.
     *
     * Toggling to a fixed mode would take a left-hander back to the right-hand default every
     * time, so the mode being left is stored and handed back. The hand is part of LayoutMode's
     * value, so remembering it takes a second preference.
     */
    private fun toggleOneHanded() {
        ime.views.vibrateForKeyPress()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ime)
        val current = prefs.getString(Prefs.LAYOUT_MODE, Prefs.DEFAULT_LAYOUT_MODE)
            ?: Prefs.DEFAULT_LAYOUT_MODE
        val remembered = prefs.getString(Prefs.LAYOUT_MODE_ONE_HANDED, null)
        val next = ActionRow.oneHandedToggle(current, remembered)
        val edit = prefs.edit().putString(Prefs.LAYOUT_MODE, next.mode)
        if (next.remember != null) edit.putString(Prefs.LAYOUT_MODE_ONE_HANDED, next.remember)
        edit.apply()
    }

    /** Peck-type on or off; the pref listener applies the state change. */
    private fun togglePeckMode() {
        ime.views.vibrateForKeyPress()
        PreferenceManager.getDefaultSharedPreferences(ime)
            .edit().putBoolean(Prefs.PECK_MODE, !ime.config.peckMode).apply()
    }

    /** ?123-chord language switch: next enabled language in canonical order. */
    private fun cycleLanguage() {
        // Shared with the shortcut notice, so the name it shows is the language this picks.
        val next = ActionRow.nextLanguage(ime.config.enabledLanguages, ime.config.language) ?: return
        ime.views.vibrateForKeyPress()
        // The preference listener does the rest: layout swap now, predictions
        // when the new dictionary finishes parsing.
        PreferenceManager.getDefaultSharedPreferences(ime)
            .edit().putString(Prefs.LANGUAGE, next).apply()
    }

    private companion object {
        // How long a shortcut's notice stays on the spacebar. Chosen, not measured: long
        // enough to read two words, gone before the next word is typed.
        const val SPACEBAR_NOTICE_MS = 1500L

        // How far each way COPY_LINE reads for the line's ends. A longer line is refused,
        // not half copied.
        const val COPY_LINE_READ_CHARS = 2000

        // How far back the clean-links action reads for the link before the cursor, and the
        // longest selection it cleans. A link is rarely past a few hundred characters.
        const val CLEAN_LINKS_READ_CHARS = 4000
    }
}
