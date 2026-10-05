package com.kinetica.keyboard.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.IOException
import org.json.JSONException
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.recyclerview.widget.RecyclerView
import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.ime.AlphaLayouts
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.LayoutTransforms
import com.kinetica.keyboard.ui.KeyboardTheme
import com.kinetica.keyboard.ui.SizePreviewView

class KeyboardPrefsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.keyboard_prefs, rootKey)
        if (rootKey == SettingsSynonyms.LONGPRESS_GROUP) addLetterRows()
        dropIconSpace(preferenceScreen)
        wireThemePreview()
        wireSizePreview()
        showVersion()
    }

    /**
     * Names the screen the user is on.
     *
     * Taken from the inflated root, not tracked in the activity, so it survives a rotation and a
     * Back press with no second copy of the titles.
     */
    override fun onResume() {
        super.onResume()
        activity?.title = preferenceScreen?.title ?: getString(R.string.settings_title)
        // A search result asks for its row before this fragment has a list to scroll, so
        // the request is held and spent here, once, whichever way it arrived.
        applyReveal()
        if (sizePreview() != null) {
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(sizeListener)
            refreshSizePreview()
        }
    }

    /** A Learn Kinetica tip that names a setting opens it. */
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val target = LearnTips.TARGETS[preference.key] ?: return super.onPreferenceTreeClick(preference)
        (activity as? SettingsActivity)?.revealSetting(target.screenKey, target.key)
        return true
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(sizeListener)
        super.onPause()
    }

    // ------------------------------------------------------------------ size preview

    private val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshSizePreview() }

    /** The screen's size preview: Size and layout has one, and the suggestion bar's screen its own. */
    private fun sizePreview(): SizePreviewPreference? =
        SIZE_PREVIEW_KEYS.firstNotNullOfOrNull { findPreference<SizePreviewPreference>(it) }

    /** The size sliders persist while the thumb moves, so the preview follows it. */
    private fun wireSizePreview() {
        if (sizePreview() == null) return
        for (key in SIZE_SLIDERS) findPreference<SeekBarPreference>(key)?.updatesContinuously = true
    }

    /**
     * Rebuilds the picture from the saved settings, through the same height arithmetic and the
     * same layout chain the service uses.
     */
    private fun refreshSizePreview() {
        val preview = sizePreview() ?: return
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val config = KeyboardConfig.from(prefs)
        val dm = resources.displayMetrics
        val keys = try {
            val bundled = (ctx.assets.list("layouts") ?: emptyArray()).map { it.removeSuffix(".json") }.toSet()
            val base = LayoutLoader.load(ctx.assets, "layouts/${AlphaLayouts.name(config, bundled)}.json")
            AlphaLayouts.build(base, config).keys
        } catch (e: IOException) {
            Log.w(TAG, "size preview layout", e)
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "size preview layout", e)
            emptyList()
        }
        val theme = KeyboardTheme.resolve(ctx, config.themeMode, config.themeColor, config.themeBrightness)
        // Both orientations whichever way the phone is held: each has its own height, and
        // landscape its own arrangement, so split can be on in one and off in the other.
        val short = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val long = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        fun spec(w: Float, h: Float, pct: Int, landscape: Boolean) = SizePreviewView.Spec(
            screenW = w,
            screenH = h,
            barPx = KeyboardHeights.barDp(config.suggestionBarDp, config.recentWords) * dm.density,
            handlePx = config.dragHandleDp * dm.density,
            boardPx = KeyboardHeights.boardPx(KeyboardHeights.targetPx(h.toInt(), dm.density, pct), config.numberRow).toFloat(),
            bottomPx = config.bottomPadDp * dm.density,
            sidePadPx = LayoutTransforms.sidePadPx(config.sidePadDp, dm.density, w),
            keys = keys,
            theme = theme,
            mode = config.layoutMode,
            landscapeArrangement = if (landscape) config.landscapeArrangement else null,
            splitGapPct = config.landscapeSplitGapPct,
        )
        preview.show(
            spec(short, long, config.heightPct, landscape = false),
            spec(long, short, config.heightPctLandscape, landscape = true),
        )
    }


    // ------------------------------------------------------------------ search

    /**
     * Every row of the whole tree, for [SettingsIndex].
     *
     * Read off the inflated screen, not parsed out of the XML: rows whose summary is their
     * selected list entry resolve themselves, a row added to the XML is searchable with no second
     * list to update, and there is no stored index to go stale.
     *
     * Only meaningful on the top-level fragment, where the whole tree is inflated.
     */
    fun searchEntries(): List<SettingsIndex.Entry> {
        val out = ArrayList<SettingsIndex.Entry>()
        val root = preferenceScreen ?: return out
        collectEntries(root, null, getString(R.string.settings_title), out)
        return out
    }

    private fun collectEntries(
        group: PreferenceGroup,
        screenKey: String?,
        screenTitle: String,
        out: MutableList<SettingsIndex.Entry>,
    ) {
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            val key = child.key ?: continue
            val title = child.title?.toString().orEmpty()
            // The theme swatch is the only row with no title, and a result needs words.
            if (title.isNotEmpty()) {
                out.add(
                    SettingsIndex.Entry(
                        key = key,
                        title = title,
                        summary = child.summary?.toString().orEmpty(),
                        screenKey = screenKey,
                        screenTitle = screenTitle,
                        terms = SettingsSynonyms.termsFor(key),
                    ),
                )
            }
            // A subscreen is both a row you can find and a screen things live on.
            if (child is PreferenceScreen) {
                collectEntries(child, key, title, out)
            } else if (child is PreferenceGroup) {
                collectEntries(child, screenKey, screenTitle, out)
            }
        }
    }

    /**
     * Scrolls to [key] and flashes it, or remembers to once there is a list.
     *
     * The flash is on the row's foreground, so the preference keeps its own background and its
     * ripple: a repainted background would leave the row looking selected.
     */
    fun revealPreference(key: String) {
        revealKey = key
        applyReveal()
    }

    private fun applyReveal() {
        val key = revealKey ?: return
        if (!isAdded || view == null) return
        revealKey = null
        scrollToPreference(key)
        val list = listView ?: return
        list.post { flashRow(list, key) }
    }

    /**
     * The on-screen row for [key], found by its title.
     *
     * androidx maps a key to an adapter position, but `PreferenceGroupAdapter` is `@RestrictTo`
     * and using it is a lint error, which this project keeps at zero. The row has just been
     * scrolled to, so it is among the visible children, and its title is what a public API
     * exposes about it. Cost: of two rows with the same title, the upper one flashes.
     */
    private fun flashRow(list: RecyclerView, key: String) {
        val want = findPreference<Preference>(key)?.title?.toString() ?: return
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) ?: continue
            val label = row.findViewById<TextView>(android.R.id.title) ?: continue
            if (label.text?.toString() == want) {
                flash(row)
                return
            }
        }
    }

    private fun flash(row: View) {
        val tv = TypedValue()
        row.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, tv, true)
        val tint = if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(row.context, tv.resourceId)
        } else {
            tv.data
        }
        val wash = ColorDrawable(tint)
        row.foreground = wash
        ObjectAnimator.ofInt(wash, "alpha", FLASH_ALPHA, 0).apply {
            duration = FLASH_MS
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        row.foreground = null
                    }
                },
            )
            start()
        }
    }

    /**
     * One row per letter on the long-press screen (#8). Each shows the letter's built-in list for
     * the active language and opens pre-filled with it, so dropping one accent is one deletion.
     * Built in code because the built-in list is only known at runtime; a failure to read it
     * leaves the rows with nothing pre-filled instead of taking the screen down.
     */
    private fun addLetterRows() {
        val screen = preferenceScreen ?: return
        val ctx = context ?: return
        val perLanguage = try {
            builtInListsPerLanguage()
        } catch (e: IOException) {
            Log.w(TAG, "built-in long-press lists unavailable", e)
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "built-in long-press lists unavailable", e)
            emptyList()
        } catch (e: RuntimeException) {
            Log.w(TAG, "built-in long-press lists unavailable", e)
            emptyList()
        }
        val maps = perLanguage.map { it.second }
        val defaults = LetterDefaults.unionPerLetter(maps, KeyboardConfig.MAX_LETTER_ALTERNATES)
        val rows = ArrayList<EditTextPreference>()
        // A letter's built-in list is the first enabled language's that has the letter, the
        // active one first, so a Latin row still has one with a Cyrillic board active.
        fun builtInFor(c: Char): List<String> = maps.firstOrNull { c in it }?.get(c).orEmpty()
        for (c in 'a'..'z') rows.add(addLetterRow(screen, c, builtInFor(c), defaults[c].orEmpty()))
        // Then each enabled board in another script, under its language's name.
        val names = resources.getStringArray(R.array.language_entries)
        val codes = resources.getStringArray(R.array.language_values)
        val shown = ('a'..'z').toHashSet()
        for ((lang, lists) in perLanguage) {
            if (Alphabet.forLanguage(lang) == Alphabet.LATIN) continue
            val letters = lists.keys.filter { shown.add(it) }
            if (letters.isEmpty()) continue
            val group = PreferenceCategory(ctx).apply {
                title = names.getOrNull(codes.indexOf(lang)) ?: lang
                isIconSpaceReserved = false
            }
            screen.addPreference(group)
            for (c in letters) rows.add(addLetterRow(group, c, builtInFor(c), defaults[c].orEmpty()))
        }
        screen.addPreference(
            Preference(ctx).apply {
                key = RESET_LETTER_LISTS_KEY
                title = getString(R.string.pref_letter_alternates_reset_title)
                isSingleLineTitle = false
                summary = getString(R.string.pref_letter_alternates_reset_summary)
                isPersistent = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.pref_letter_alternates_reset_title)
                        .setMessage(R.string.pref_letter_alternates_reset_message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> rows.forEach { it.text = null } }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            },
        )
    }

    /** One letter's row: its built-in list as the summary and the starting text, and Default. */
    private fun addLetterRow(
        group: PreferenceGroup,
        c: Char,
        own: List<String>,
        default: List<String>,
    ): EditTextPreference {
        val ownText = own.joinToString(" ")
        val letter = c.uppercaseChar().toString()
        val row = EditTextPreference(group.context)
        row.key = Prefs.letterAlternatesKey(c)
        row.title = letter
        row.isSingleLineTitle = false
        row.dialogTitle = getString(R.string.pref_letter_alternates_dialog_title, letter)
        row.dialogMessage = getString(R.string.pref_letter_alternates_note_summary)
        row.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
            val t = p.text
            when {
                !t.isNullOrBlank() -> t
                own.isEmpty() -> getString(R.string.pref_letter_alternates_none)
                else -> getString(R.string.pref_letter_alternates_builtin, ownText)
            }
        }
        val defaultText = default.joinToString(" ")
        row.setOnBindEditTextListener { edit ->
            if (row.text.isNullOrBlank() && ownText.isNotEmpty()) {
                edit.setText(ownText)
                edit.setSelection(ownText.length)
            }
            // Beside the field, not a dialog button: EditTextPreference owns those.
            if (defaultText.isNotEmpty()) {
                (edit.parent as? ViewGroup)?.addView(
                    Button(edit.context).apply {
                        text = getString(R.string.pref_letter_alternates_default)
                        setOnClickListener {
                            edit.setText(defaultText)
                            edit.setSelection(defaultText.length)
                        }
                    },
                )
            }
        }
        // The built-in list typed back, or nothing, is no override: stored as absent so a
        // later change to the language's own list still reaches this letter.
        row.setOnPreferenceChangeListener { _, value ->
            val list = KeyboardConfig.parseAlternates(value as? String, KeyboardConfig.MAX_LETTER_ALTERNATES)
            if (list.isEmpty() || list == own) {
                row.text = null
                false
            } else {
                true
            }
        }
        group.addPreference(row)
        return row
    }

    /**
     * Each enabled language's long-press lists as the keyboard would build them with no list of
     * the user's, the active language first, keyed by its board's letters.
     */
    private fun builtInListsPerLanguage(): List<Pair<String, Map<Char, List<String>>>> {
        val ctx = context ?: return emptyList()
        val prefs = preferenceManager.sharedPreferences ?: return emptyList()
        val config = KeyboardConfig.from(prefs).copy(letterAlternates = emptyMap())
        val bundled = (ctx.assets.list("layouts") ?: emptyArray()).map { it.removeSuffix(".json") }.toSet()
        val langs = (listOf(config.language) + config.enabledLanguages).distinct()
        return langs.map { lang ->
            val c = config.copy(language = lang)
            val base = LayoutLoader.load(ctx.assets, "layouts/${AlphaLayouts.name(c, bundled)}.json")
            lang to AlphaLayouts.build(base, c).keys
                .filter { it.isLetter }
                .associate { it.output[0] to it.alternates }
        }
    }

    /**
     * No preference on this screen has an icon, so none should reserve the icon gutter, which on
     * a 21:9 phone squeezes the description column.
     *
     * Walked in code, not set per element in the XML: a new row would arrive without the
     * attribute, and this also reaches the categories and the theme preview.
     */
    private fun dropIconSpace(group: PreferenceGroup) {
        group.isIconSpaceReserved = false
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            child.isIconSpaceReserved = false
            if (child is PreferenceGroup) dropIconSpace(child)
        }
    }

    /**
     * The version of the build that is running.
     *
     * Read from the installed package, not a compile-time constant, so the line says which APK is
     * on this phone. The developer build reports its `-dev` suffix, so the row also tells the two
     * installed apps apart.
     */
    private fun showVersion() {
        val pref = findPreference<Preference>(VERSION_KEY) ?: return
        val ctx = context ?: return
        pref.summary = try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        } catch (e: PackageManager.NameNotFoundException) {
            // Cannot happen for our own package; a blank row is better than a crash.
            ""
        }
    }

    /**
     * Keeps the palette swatch under the hue slider in step with the three preferences that decide
     * it. The change listeners take the new value from the callback: onPreferenceChange runs
     * before the value is persisted, so reading preferences here would show the previous palette.
     */
    private fun wireThemePreview() {
        val preview = findPreference<ThemePreviewPreference>(PREVIEW_KEY) ?: return
        val hue = findPreference<SeekBarPreference>(Prefs.THEME_HUE)
        val mode = findPreference<ListPreference>(Prefs.THEME_MODE)
        val brightness = findPreference<ListPreference>(Prefs.THEME_BRIGHTNESS)

        // Repaint while the thumb moves; otherwise the swatch only catches up on release.
        hue?.updatesContinuously = true

        fun refresh(newHue: Int? = null, newMode: String? = null, newBrightness: String? = null) {
            val prefs = preferenceManager.sharedPreferences ?: return
            // KeyboardConfig owns the migration from the retired colour list, so going through it
            // keeps the swatch correct on first open, before the hue preference is written.
            val config = KeyboardConfig.from(prefs)
            val primary = newHue?.let { KeyboardTheme.primaryForHue(it.toFloat()) }
                ?: config.themeColor
            val resolvedMode = newMode ?: config.themeMode
            preview.show(
                KeyboardTheme.resolve(
                    requireContext(),
                    resolvedMode,
                    primary,
                    newBrightness ?: config.themeBrightness,
                ),
                getString(
                    if (KeyboardTheme.hueAffects(resolvedMode)) {
                        R.string.theme_preview_note_custom
                    } else {
                        R.string.theme_preview_note_fixed
                    },
                ),
            )
        }

        hue?.setOnPreferenceChangeListener { _, value ->
            refresh(newHue = value as? Int)
            true
        }
        mode?.setOnPreferenceChangeListener { _, value ->
            refresh(newMode = value as? String)
            true
        }
        brightness?.setOnPreferenceChangeListener { _, value ->
            refresh(newBrightness = value as? String)
            true
        }
        refresh()
    }

    private var revealKey: String? = null

    private companion object {
        const val TAG = "KineticaPrefs"
        const val RESET_LETTER_LISTS_KEY = "pref_letter_alternates_reset"
        const val PREVIEW_KEY = "pref_theme_preview"
        val SIZE_PREVIEW_KEYS = listOf("pref_size_preview", "pref_bar_preview")
        val SIZE_SLIDERS = listOf(
            Prefs.KEYBOARD_HEIGHT_PCT, Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE, Prefs.SUGGESTION_BAR_DP,
            Prefs.SIDE_PAD_DP, Prefs.BOTTOM_PAD_DP, Prefs.DRAG_HANDLE_DP, Prefs.LANDSCAPE_SPLIT_GAP_PCT,
            Prefs.HOME_ROW_SPREAD_PCT,
        )
        const val VERSION_KEY = "pref_version"
        // Visible enough to find by eye, faint enough not to read as a selection, and long
        // enough to still be fading when the scroll settles.
        const val FLASH_ALPHA = 90
        const val FLASH_MS = 900L
    }
}
