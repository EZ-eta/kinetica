package com.kinetica.keyboard.ime

import android.content.res.Configuration
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LandscapeArrangement
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.settings.KeyboardHeights
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.ui.EmojiPickerView
import com.kinetica.keyboard.ui.EmojiRecents
import com.kinetica.keyboard.ui.Hsv
import com.kinetica.keyboard.ui.KeyboardTheme

/**
 * How the config dresses the keyboard's views: theme, heights and bounds, the board built for the
 * field and the language, the emoji panel, and the key-press buzz. The views themselves are the
 * service's, made in onCreateInputView.
 */
internal class KeyboardViews(private val ime: KineticaIME) {

    // Bundled layout names, listed once. The alpha layer falls back to plain qwerty when no
    // qwerty_<lang>.json is bundled, so a new language never inherits another language's
    // accent alternates.
    private val bundledLayouts: Set<String> by lazy {
        (ime.assets.list("layouts") ?: emptyArray())
            .map { it.removeSuffix(".json") }
            .toSet()
    }

    private val layouts = HashMap<String, KeyboardLayout>()

    /** Alpha layout with settings-driven mutations applied; the chain is [AlphaLayouts]. */
    // The field kind the letters on screen were built for: a new field of another kind
    // rebuilds them, so an email field's `@` does not follow the user into a message.
    var layoutFieldKind = FieldKind.TEXT

    // The number pad was put up by a number field, not chosen: the next field takes it down.
    var numpadAuto = false

    var vibrator: Vibrator? = null

    fun alphaLayout(): KeyboardLayout =
        AlphaLayouts.build(layoutFor(AlphaLayouts.name(ime.config, bundledLayouts)), ime.config, ime.editorState.fieldKind)
            .also { layoutFieldKind = ime.editorState.fieldKind }

    fun openEmojiPicker() {
        val container = ime.containerView ?: return
        ime.autospace.cancel()
        ime.commit.finalizePendingWord()
        // Built lazily and guarded: a throw from the picker's construction on this key path
        // kills the service. An optional panel that does not open is the right failure.
        val picker = ime.emojiPicker ?: try {
            EmojiPickerView(
                ime,
                onEmoji = { ime.learner.recordEmojiUse(it); ime.commitTracked(it) },
                onBackspace = { ime.backspace.onKey() },
                onClose = { closeEmojiPicker() },
            ).also { ime.emojiPicker = it }
        } catch (e: RuntimeException) {
            Log.w(KineticaIME.TAG, "emoji picker unavailable", e)
            return
        }
        // Built lazily, so it misses the applyViewConfig that ran at startup.
        picker.theme = KeyboardTheme.resolve(
            ime, ime.config.themeMode, ime.config.themeColor, ime.config.themeBrightness,
        )
        // On open, not on every pick: the panel stays up while the user taps, and
        // re-ordering the first tab under a moving finger would shift the next cell.
        picker.recents = EmojiRecents.ordered(ime.learner.emojiUses.values)
        container.showEmojiPicker(picker)
    }

    fun closeEmojiPicker() {
        ime.containerView?.hideEmojiPicker(ime.emojiPicker)
    }

    fun layoutFor(name: String): KeyboardLayout =
        layouts.getOrPut(name) { LayoutLoader.load(ime.assets, "layouts/$name.json") }

    // Bounds arithmetic lives in KeyboardHeights, which is pure and testable: an inverted
    // min..max range once made coerceIn throw and crashed the process.
    fun minKeyboardPx(): Int = numberRowPx(
        KeyboardHeights.minPx(ime.resources.displayMetrics.heightPixels, ime.resources.displayMetrics.density),
    )

    fun maxKeyboardPx(): Int =
        numberRowPx(KeyboardHeights.maxPx(ime.resources.displayMetrics.heightPixels))

    /**
     * The board's height for a letter area of [letterPx]: a row taller with the numbers row, so
     * the height setting and its bounds keep meaning the letters.
     */
    private fun numberRowPx(letterPx: Int): Int = KeyboardHeights.boardPx(letterPx, ime.config.numberRow)

    fun persistHeightPct(px: Int) {
        val letterPx = KeyboardHeights.letterPx(px, ime.config.numberRow)
        val pct = KeyboardHeights.pctFor(letterPx, ime.resources.displayMetrics.heightPixels)
        val key = if (isLandscape()) Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE else Prefs.KEYBOARD_HEIGHT_PCT
        PreferenceManager.getDefaultSharedPreferences(ime)
            .edit().putInt(key, pct).apply()
    }

    private fun isLandscape(): Boolean =
        ime.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Pushes the current [config] and editor-derived flags into the views. */
    fun applyViewConfig() {
        val kv = ime.keyboardView ?: return
        kv.zenMode = ime.config.zenMode
        // A swipe trail visually leaks what was typed, so a password field never draws
        // one. A no-learning field does: the trail shows the user their own thumb and is
        // gone in 250 ms. In peck mode swipes do nothing, so a trail would advertise a
        // gesture that has no effect.
        kv.trailsEnabled = !ime.editorState.privateMode && !ime.config.peckMode
        // Per field: the key names the action enter will run, and its hold offers a newline
        // whenever enter itself will not write one.
        kv.enterNewlineCell =
            EnterBehavior.tap(ime.editorState, ime.config.enterAction, ime.config.enterHoldSends) is EnterBehavior.Result.Action
        kv.enterActionCell = EnterBehavior.holdAction(ime.editorState, ime.config.enterHoldSends) != null
        kv.enterLabel = EnterBehavior.labelKind(ime.editorState, ime.config.enterAction, ime.config.enterHoldSends)
            ?.let { enterLabelText(it) }
        if (!ime.config.typingSpeed || ime.editorState.privateMode) kv.speedLabel = null
        val theme = KeyboardTheme.resolve(
            ime, ime.config.themeMode, ime.config.themeColor, ime.config.themeBrightness,
        )
        kv.theme = theme
        ime.suggestionBar?.theme = theme
        ime.containerView?.applyTheme(theme)
        ime.emojiPicker?.theme = theme
        applyNavigationBarColor(theme)
        kv.trailBaseHue =
            if (ime.config.trailColorMode == "theme") theme.accentHue else ime.config.trailBaseHue
        kv.trailCycles = ime.config.trailColorMode == "rainbow"
        kv.longPressMs = ime.config.longPressMs
        kv.chordArmMs = ime.config.chordArmMs
        kv.chordLabelsEnabled = ime.config.chordLabels
        kv.spaceChordArmMs = ime.config.spaceChordArmMs
        kv.layoutMode = ime.config.layoutMode
        kv.landscapeSplitGapPct = ime.config.landscapeSplitGapPct
        val arranged = LandscapeArrangement.appliesTo(
            isLandscape(), ime.resources.configuration.screenWidthDp, ime.config.wideArrangement,
        )
        kv.landscapeArrangement = if (arranged) ime.config.landscapeArrangement else null
        kv.popupColumns = ime.config.popupColumns
        kv.autospaceDot = ime.config.autospace
        kv.backspaceCharSlide = ime.config.backspaceCharSlide
        kv.backspaceStepDp = ime.config.backspaceStepDp
        kv.backspacePunctuationStops = ime.config.backspacePunctuationStops
        kv.spacebarStepDp = ime.config.spacebarStepDp
        kv.spacebarWordSlide = ime.config.spacebarWordSlide
        kv.spacelessSpace = ime.config.spacelessSpace
        kv.doubleSpacePeriod = ime.config.doubleSpacePeriod
        kv.editFromMenu = ime.config.editFromMenu
        kv.languageLabel = spacebarLabel()
        // When enabled, layer the layout-derived implicit alternate
        // swipes under the user/built-in bindings (explicit shadows implicit).
        // Synthesized from the active-language alpha layout, so qwerty_es "n"
        // yields "!" and each language's digits/symbols follow its own keys.
        kv.edgeSwipeBindings = if (ime.config.alternateSwipes) {
            EdgeSwipeBindings.withImplicitAlternates(alphaLayout(), ime.config.edgeSwipes)
        } else {
            ime.config.edgeSwipes
        }
        ime.suggestionBar?.reinforceIncrement = ime.config.reinforceIncrement
        ime.suggestionBar?.reinforceStepDp = ime.config.reinforceStepDp
        ime.suggestionBar?.badgesWhileAdjusting = ime.config.badgesWhileAdjusting
        ime.suggestionBar?.textScale = ime.config.barTextScale
        ime.suggestionBar?.recentTextScale = ime.config.recentTextScale
        ime.suggestionBar?.compactWords = ime.config.barCompact
        ime.suggestionBar?.recentSwipeReversed = ime.config.recentSwipeReversed
        ime.suggestionBar?.retypeButton = ime.config.retypeButton
        ime.suggestionBar?.retypeButtonDp = ime.config.retypeButtonDp
        // Resolved here, not in the views, so the language cell drops out below two enabled
        // languages on both surfaces alike.
        ime.commands.barActions = ActionRow.resolve(
            ime.config.barActions, ime.config.enabledLanguages.size, ActionRow.ALL.size, ime.config.barActionOrder,
        )
        ime.commands.menuActions = ActionRow.resolve(
            ime.config.menuActions, ime.config.enabledLanguages.size, ActionRow.ALL.size, ime.config.menuActionOrder,
        )
        ime.suggestionBar?.actions = ime.commands.barActions.map { ActionRow.glyph(it) }
        kv.modeMenuCells = ime.commands.menuActions.map { ActionRow.glyph(it) }
        ime.keyboardView?.sidePadDp = ime.config.sidePadDp
        ime.containerView?.setBottomGap(dpToPx(ime.config.bottomPadDp.toFloat()))
        ime.containerView?.setBarHeight(dpToPx(barHeightDp()))
        ime.containerView?.setHeightBounds(minKeyboardPx(), maxKeyboardPx())
        ime.suggestionBar?.tallFactor = if (ime.config.recentWords) KeyboardHeights.RECENT_BAR_TALL else 1f
        ime.containerView?.setHandleHeight(dpToPx(ime.config.dragHandleDp.toFloat()))
        val targetH = keyboardHeightPx()
        val lp = kv.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            kv.requestLayout()
        }
    }

    /**
     * Paints the system navigation bar to match the keyboard; left alone it is a black band
     * under a themed keyboard.
     *
     * Icon contrast comes from the background's luminance, so it holds for a custom hue too.
     * navigationBarColor is deprecated once targetSdk 35 enforces edge-to-edge; at 34 it still
     * applies, and raising the target is a reproducible-build change of its own.
     */
    private fun applyNavigationBarColor(theme: KeyboardTheme) {
        val w = ime.window?.window ?: return
        @Suppress("DEPRECATION")
        w.navigationBarColor = theme.background
        val lightBackground = Hsv.luminance(theme.background) > 0.5
        WindowInsetsControllerCompat(w, w.decorView)
            .isAppearanceLightNavigationBars = lightBackground
    }

    /**
     * Spacebar status text. The language code shows only with several languages enabled,
     * since alone it carries no information. Peck mode appends a TAP marker so the disabled
     * gesture engine is visible at a glance.
     */
    private fun spacebarLabel(): String? {
        val parts = ArrayList<String>(2)
        if (ime.config.enabledLanguages.size > 1) parts.add(ime.config.language.uppercase())
        if (ime.config.peckMode) parts.add("TAP")
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    fun keyboardHeightPx(): Int = numberRowPx(
        KeyboardHeights.targetPx(
            ime.resources.displayMetrics.heightPixels,
            ime.resources.displayMetrics.density,
            if (isLandscape()) ime.config.heightPctLandscape else ime.config.heightPct,
        ),
    )

    fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, ime.resources.displayMetrics,
    ).toInt()

    /** The bar's height: one row, or taller while recent words share it. */
    fun barHeightDp(): Float = KeyboardHeights.barDp(ime.config.suggestionBarDp, ime.config.recentWords)

    private fun enterLabelText(label: EnterBehavior.Label): String = when (label) {
        is EnterBehavior.Label.Custom -> label.text
        EnterBehavior.Label.SEARCH -> ime.getString(R.string.enter_label_search)
        EnterBehavior.Label.SEND -> ime.getString(R.string.enter_label_send)
        EnterBehavior.Label.GO -> ime.getString(R.string.enter_label_go)
        EnterBehavior.Label.NEXT -> ime.getString(R.string.enter_label_next)
        EnterBehavior.Label.DONE -> ime.getString(R.string.enter_label_done)
        EnterBehavior.Label.PREVIOUS -> ime.getString(R.string.enter_label_previous)
    }

    fun vibrateForKeyPress() {
        if (!ime.config.vibration) return
        val amplitude = when (ime.config.vibrationIntensity) {
            1 -> 60
            3 -> 255
            else -> 140
        }
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(12, amplitude)
        } else {
            VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }
}
