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

package com.kinetica.keyboard.ime

import android.content.ClipboardManager
import android.util.Log
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.Clip
import com.kinetica.keyboard.data.ClipVault
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.settings.KeyboardHeights
import com.kinetica.keyboard.ui.ClipPaneView
import com.kinetica.keyboard.ui.KeyboardTheme

/**
 * The clipboard history on the keyboard's side: the listener that keeps copies, and the pane in
 * the bar's place that pastes them. The clips themselves are [ClipVault]'s. The listener is
 * registered only while the switch is on; Android hands a copy to the keyboard in use only.
 *
 * The trace gets lengths, counts and reasons, never a clip's text.
 */
internal class ClipKeeper(private val ime: KineticaIME) {

    private var view: ClipPaneView? = null
    private var listening = false

    var active = false
        private set

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    private fun clipboard(): ClipboardManager? = ime.getSystemService(ClipboardManager::class.java)

    /**
     * Follows the switches: on, the listener is added and expired clips deleted; off, the listener
     * goes and [ClipVault] clears memory, file and key. Run at create, on every change of either
     * switch and at each field's start.
     */
    fun configure(why: String) {
        val on = ime.config.clipHistory
        ClipVault.configure(ime, on, ime.config.clipShort, why)
        if (on) {
            if (!listening) {
                clipboard()?.addPrimaryClipChangedListener(listener)
                listening = true
            }
            ClipVault.live(ime, System.currentTimeMillis())
            if (active) refresh()
        } else {
            stop()
            close("off")
        }
    }

    /** The listener removed: switched off, or the service going. */
    fun stop() {
        if (!listening) return
        clipboard()?.removePrimaryClipChangedListener(listener)
        listening = false
    }

    private fun onClipChanged() {
        val clip = try {
            clipboard()?.primaryClip
        } catch (e: SecurityException) {
            // Android refuses the clipboard to a keyboard not in use; nothing to keep.
            Log.w(KineticaIME.TAG, "clipboard refused", e)
            null
        } ?: return
        val sensitive = clip.description?.extras?.getBoolean(ClipIntake.EXTRA_IS_SENSITIVE, false) == true
        val text = if (clip.itemCount == 1) clip.getItemAt(0).text?.toString() else null
        val skip = ClipIntake.skipReason(
            enabled = ime.config.clipHistory,
            teachesNothing = ime.editorState.teachesNothing,
            sensitive = sensitive,
            items = clip.itemCount,
            text = text,
        )
        if (skip != null || text == null) {
            DecodeTrace.log { "  clip skipped why=$skip" }
            return
        }
        if (!ClipVault.keep(ime, text, System.currentTimeMillis())) {
            DecodeTrace.log { "  clip skipped why=long len=${text.length}" }
            return
        }
        val n = ClipVault.live(ime, System.currentTimeMillis()).size
        DecodeTrace.log { "  clip kept len=${text.length} n=$n" }
        if (active) refresh()
    }

    /** The views were rebuilt: the pane went with the old container. */
    fun reset() {
        active = false
        view = null
    }

    fun open(src: String) {
        if (active) return
        // No pane in a password field: a clip shown there is a clip read over a shoulder.
        if (ime.editorState.privateMode) {
            DecodeTrace.log { "  clip pane refused why=private" }
            ime.commands.showSpacebarNotice(ime.getString(R.string.clips_private))
            return
        }
        if (!ime.config.clipHistory) {
            DecodeTrace.log { "  clip pane refused why=off" }
            ime.commands.showSpacebarNotice(ime.getString(R.string.clips_off))
            return
        }
        val container = ime.containerView ?: return
        ime.emojiSearch.close("clips")
        ime.views.closeEmojiPicker()
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        val v = view ?: try {
            ClipPaneView(
                ime,
                rowPx = ime.views.dpToPx(KeyboardHeights.EMOJI_SEARCH_ROW_DP),
                onPaste = { paste(it) },
                onDelete = { delete(it) },
                onClearAll = { clearAll() },
                onClose = { close("x") },
            ).also { view = it }
        } catch (e: RuntimeException) {
            // The bar stays: an optional pane that does not open is the right failure.
            Log.w(KineticaIME.TAG, "clip pane unavailable", e)
            return
        }
        v.theme = KeyboardTheme.resolve(ime, ime.config.themeMode, ime.config.themeColor, ime.config.themeBrightness)
        v.overBackdrop = ime.views.backdrop.bitmap != null
        container.showClipPane(v, ime.views.dpToPx(KeyboardHeights.emojiSearchDp(ime.config.suggestionBarDp)))
        active = true
        val n = refresh()
        DecodeTrace.log { "  clip pane open src=$src n=$n" }
    }

    fun close(why: String) {
        if (!active) return
        active = false
        ime.containerView?.hideClipPane(view)
        DecodeTrace.log { "  clip pane close why=$why" }
    }

    /** The action, from the bar, the menu or a chord: opens the pane, or closes it if up. */
    fun toggle(src: String) = if (active) close("action") else open(src)

    /** The pane shows the clips alive now; an expired one is deleted on this read. */
    private fun refresh(): Int {
        val clips = ClipVault.live(ime, System.currentTimeMillis())
        view?.show(clips)
        return clips.size
    }

    private fun paste(clip: Clip) {
        val now = System.currentTimeMillis()
        // Read again at the tap: a clip that expired with the pane open is gone, not pasted.
        if (ime.editorState.privateMode || ClipVault.live(ime, now).none { it.id == clip.id }) {
            DecodeTrace.log { "  clip paste refused" }
            refresh()
            return
        }
        val text = if (ime.config.cleanLinksOnPaste) LinkCleaner.clean(clip.text) else clip.text
        close("paste")
        DecodeTrace.log { "  clip paste len=${text.length} age_s=${(now - clip.copiedAt) / 1000}" }
        ime.commands.pasteText(text)
    }

    private fun delete(clip: Clip) {
        ClipVault.delete(ime, clip.id)
        val n = refresh()
        DecodeTrace.log { "  clip delete n=$n" }
    }

    private fun clearAll() {
        ClipVault.clear(ime, "pane")
        refresh()
    }
}
