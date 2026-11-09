package com.kinetica.keyboard.ime

import android.os.SystemClock
import android.util.Log
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.keys.ShiftState
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.KeyboardHeights
import com.kinetica.keyboard.ui.EmojiRecents
import com.kinetica.keyboard.ui.EmojiSearch
import com.kinetica.keyboard.ui.EmojiSearchView
import com.kinetica.keyboard.ui.KeyboardTheme

/**
 * The emoji search panel and where the keyboard's input goes while it is up: letters, a swiped
 * word, space and backspace edit the query, never the field. Each key path asks [active] at its
 * top, as the one-shot Ctrl does, so nothing upstream of the editor runs for a search key.
 */
internal class EmojiSearchMode(private val ime: KineticaIME) {

    private val query = EmojiQuery()
    private var view: EmojiSearchView? = null
    private var results: List<String> = emptyList()

    // A swiped word ends on the pause that would autospace it in the field.
    private var wordEndPending = false
    private val wordEnd = Runnable {
        wordEndPending = false
        if (!active || !query.inWord) return@Runnable
        DecodeTrace.log { "  emoji search word end" }
        space()
    }

    var active = false
        private set

    /** The views were rebuilt: the panel went with the old container. */
    fun reset() {
        cancelWordEnd()
        active = false
        view = null
        query.clear()
    }

    fun open(src: String) {
        if (active) return
        val container = ime.containerView ?: return
        ime.views.closeEmojiPicker()
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        val v = view ?: try {
            EmojiSearchView(
                ime,
                rowPx = ime.views.dpToPx(KeyboardHeights.EMOJI_SEARCH_ROW_DP),
                onPick = { ch, rank -> pick(ch, rank) },
                onClose = { close("x") },
            ).also { view = it }
        } catch (e: RuntimeException) {
            // The bar stays: an optional panel that does not open is the right failure.
            Log.w(KineticaIME.TAG, "emoji search unavailable", e)
            return
        }
        v.theme = KeyboardTheme.resolve(ime, ime.config.themeMode, ime.config.themeColor, ime.config.themeBrightness)
        v.overBackdrop = ime.views.backdrop.bitmap != null
        container.showEmojiSearch(v, ime.views.dpToPx(KeyboardHeights.emojiSearchDp(ime.config.suggestionBarDp)))
        active = true
        query.clear()
        DecodeTrace.log { "  emoji search open src=$src recents=${ime.learner.emojiUses.size}" }
        refresh()
    }

    fun close(why: String) {
        if (!active) return
        cancelWordEnd()
        active = false
        query.clear()
        // A swipe still decoding belongs to the search: its result must not reach the field.
        ime.composer?.clear()
        ime.containerView?.hideEmojiSearch(view)
        DecodeTrace.log { "  emoji search close why=$why" }
    }

    /** The action, from the bar, the menu or a chord: opens the panel, or closes it if up. */
    fun toggle(src: String) = if (active) close("action") else open(src)

    // ---------------------------------------------------------------- input, while active

    /**
     * Every gesture joins the composer's word in hand, as in the field: a tapped letter shows at
     * once, a swipe once decoded ([onDecoded]). Each new token supersedes the decode before it.
     */
    fun onToken(token: InputToken) {
        cancelWordEnd()
        val comp = ime.composer
        when (token) {
            is TapToken -> {
                // A word the composer cannot hold stands as typed letters.
                if (comp == null) query.typed(tapLetter(token)) else query.tapped(tapLetter(token))
                comp?.onToken(token)
                refresh()
            }
            is SwipeToken -> {
                if (comp == null) return
                query.beginSwipe()
                comp.onToken(token)
            }
        }
        if (ime.shift.state == ShiftState.State.SHIFT) {
            ime.shift.onLetterCommitted()
            ime.keyboardView?.setShiftUppercase(ime.shift.isShifted)
        }
    }

    private fun tapLetter(token: TapToken): String = ime.shift.apply(ime.boardAlphabet.charOf(token.code)).toString()

    /** A decode of the word in hand: with a swipe in it, its word becomes the query's last. */
    fun onDecoded(word: String?) {
        if (word == null) return
        query.decoded(word)
        refresh()
        armWordEnd()
    }

    /**
     * The field's swipe autospace delay, counted from the last lift. Off with autospace, or with
     * words ending on space, as in the field; a thumb still down waits for its token.
     */
    private fun armWordEnd() {
        val comp = ime.composer ?: return
        if (!query.inWord || !comp.hasSwipeToken() || ime.engine.hasActivePointers()) return
        if (!ime.config.autospace || ime.config.wordEndsOnSpace) return
        cancelWordEnd()
        wordEndPending = true
        val wait = autospaceWait(ime.config.autospaceDelayMs, comp.lastTokenEnd, SystemClock.uptimeMillis())
        ime.mainHandler.postDelayed(wordEnd, wait)
    }

    /** A new touch: the word may go on. */
    fun cancelWordEnd() {
        if (wordEndPending) {
            ime.mainHandler.removeCallbacks(wordEnd)
            wordEndPending = false
        }
    }

    /** True when the key was the search's: the caller does nothing more with it. */
    fun onKeyTap(key: Key): Boolean {
        if (!active) return false
        when (key.type) {
            KeyType.CHAR -> if (key.output.codePoints().allMatch { Character.isLetterOrDigit(it) }) type(key.output)
            KeyType.SPACE -> space()
            KeyType.BACKSPACE -> delete()
            KeyType.ENTER -> {
                results.firstOrNull()?.let { pick(it, 0) }
                close("enter")
            }
            KeyType.EMOJI -> {
                close("emoji")
                ime.views.openEmojiPicker()
            }
            else -> return false
        }
        return true
    }

    /** A long-press cell: an accented letter joins the query; the emoji cell opens the pane. */
    fun onAlternate(text: String): Boolean {
        if (!active) return false
        if (text == LayoutMutations.EMOJI_ALTERNATE) {
            close("emoji")
            ime.views.openEmojiPicker()
        } else if (text.codePoints().allMatch { Character.isLetterOrDigit(it) }) {
            type(text)
        }
        return true
    }

    /** Backspace, held or slid: one character of the query a step, never the field's text. */
    fun delete() {
        endWordInHand()
        if (query.backspace()) refresh()
    }

    fun space() {
        endWordInHand()
        query.space()
        refresh()
    }

    /** A key or accent outside the gesture path: the word in hand ends, the letter follows it. */
    private fun type(s: String) {
        endWordInHand()
        query.typed(s)
        refresh()
    }

    /** The query settles the word; the composer must forget it, or the next token joins it. */
    private fun endWordInHand() {
        cancelWordEnd()
        ime.composer?.clear()
    }

    private fun pick(ch: String, rank: Int) {
        DecodeTrace.log { "  emoji search pick ch=$ch rank=$rank" }
        ime.learner.recordEmojiUse(ch)
        ime.commitTracked(ch)
    }

    /** An empty query shows the frequently used emoji, the pane's first tab, then the catalog's first. */
    private fun refresh() {
        val v = view ?: return
        val assets = ime.views.emojiAssets
        val uses = ime.learner.emojiUses.values
        results = if (query.text.isBlank()) {
            val recents = EmojiRecents.ordered(uses).filter { assets.canDraw(it) }
            // Twice the strip: enough to fill it whichever recents the catalog's head repeats.
            EmojiRecents.strip(recents, assets.catalog.entries.take(2 * EmojiRecents.MAX).map { it.ch })
        } else {
            val langs = (ime.config.enabledLanguages + ENGLISH).distinct().map { assets.words(it) }
            EmojiSearch.rank(query.text, assets.catalog.entries, langs, uses.associate { it.emoji to it.count })
                .map { it.ch }
        }
        v.show(query.text, results)
        if (query.text.isNotBlank()) {
            // A field that teaches nothing keeps its typing out of the trace too.
            val q = if (ime.editorState.privateMode) "<${query.text.length}>" else query.text
            DecodeTrace.log { "  emoji search query=$q results=${results.size}" }
        }
    }

    private companion object {
        /** Searched beside the enabled languages: the hand-written words are English. */
        const val ENGLISH = "en"
    }
}
