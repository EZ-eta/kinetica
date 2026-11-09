package com.kinetica.keyboard.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.io.IOException
import org.json.JSONException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import androidx.recyclerview.widget.RecyclerView
import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.ime.AlphaLayouts
import com.kinetica.keyboard.ime.DictationHandoff
import com.kinetica.keyboard.ime.KeyboardViews
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.LandscapeArrangement
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.LayoutTransforms
import com.kinetica.keyboard.layout.SymbolRows
import com.kinetica.keyboard.ui.KeyboardTheme
import com.kinetica.keyboard.ui.SizePreviewView

class KeyboardPrefsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Before inflation, which would store and show the defaults over the old settings.
        preferenceManager.sharedPreferences?.let {
            LanguageMode.migrate(it)
            ColorPrefs.migrate(it)
            OrientationPrefs.migrate(it)
        }
        // The changed-settings screen inflates the tree to learn each row's default: rows ask the
        // recorder for their value and write nothing.
        if (rootKey == CHANGED_SCREEN) preferenceManager.preferenceDataStore = defaultsSeen
        // The guided setup shows a few of these rows through the same wiring, from its own XML.
        setPreferencesFromResource(arguments?.getInt(ARG_XML, 0)?.takeIf { it != 0 } ?: R.xml.keyboard_prefs, rootKey)
        if (rootKey == SettingsSynonyms.LONGPRESS_GROUP) addLetterRows()
        if (rootKey == SettingsSynonyms.SYMBOL_PAGES_GROUP) addSymbolRows()
        // A search hit, a tip or the keyboard asked for one row: the screen opens with its
        // Advanced rows shown, or a row under them has no place in the list to scroll to.
        if (arguments?.getString(ARG_REVEAL) != null) preferenceScreen?.initialExpandedChildrenCount = Int.MAX_VALUE
        if (rootKey == null && arguments?.getInt(ARG_XML, 0)?.takeIf { it != 0 } == null) addSearchRow()
        dropIconSpace(preferenceScreen)
        wireThemePreview(night = false)
        wireThemePreview(night = true)
        wireBackdrop(night = false)
        wireBackdrop(night = true)
        wireLookSets()
        wireSizePreview()
        wireSizeGroups()
        wireLookGroups()
        wireSymbolRows()
        wireDictation()
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
        refreshBackdropRow(night = false)
        refreshBackdropRow(night = true)
        // The user comes back from the system's keyboard list here.
        refreshDictationStatus()
        // Not in onCreatePreferences: a screen returned to from the back stack skips it.
        refreshHomeSummaries()
        refreshChangedRows()
        if (sizePreview() != null) {
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(sizeListener)
            refreshSizePreview()
        }
        // Written while away (the keyboard, another screen), then live while on screen.
        preferenceManager.sharedPreferences?.let { prefs ->
            preferenceScreen?.let { RowSync.applyAll(it, prefs) }
            prefs.registerOnSharedPreferenceChangeListener(rowSync)
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
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(rowSync)
        super.onPause()
    }

    /** A value written while this screen is open, by the keyboard above it or a dialog, shows in its row. */
    private val rowSync = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != null) findPreference<Preference>(key)?.let { RowSync.apply(it, prefs) }
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
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val dm = resources.displayMetrics
        // Both orientations whichever way the phone is held: each has its own sizes, and
        // landscape its own arrangement, so split can be on in one and off in the other.
        val short = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val long = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        // The look the keyboard draws now, night or day.
        val night = LookSets.systemNight(resources.configuration.uiMode)
        fun spec(landscape: Boolean): SizePreviewView.Spec {
            val config = KeyboardConfig.from(prefs, landscape, night)
            val w = if (landscape) long else short
            val h = if (landscape) short else long
            val pct = if (landscape) config.heightPctLandscape else config.heightPct
            return SizePreviewView.Spec(
                screenW = w,
                screenH = h,
                barPx = KeyboardHeights.barDp(config.suggestionBarDp, config.recentWords) * dm.density,
                handlePx = config.dragHandleDp * dm.density,
                boardPx = KeyboardHeights.boardPx(KeyboardHeights.targetPx(h.toInt(), dm.density, pct), config.numberRow).toFloat(),
                bottomPx = config.bottomPadDp * dm.density,
                sidePadPx = LayoutTransforms.sidePadPx(config.sidePadDp, dm.density, w),
                keys = previewKeys(ctx, config),
                theme = KeyboardTheme.resolve(ctx, config.themeMode, config.themeColor, config.themeBrightness),
                mode = config.layoutMode,
                landscapeArrangement = config.landscapeArrangement.takeIf {
                    LandscapeArrangement.appliesTo(landscape, (w / dm.density).toInt(), config.wideArrangement)
                },
                splitGapPct = config.landscapeSplitGapPct,
            )
        }
        // The suggestion bar's sliders are the portrait ones, so its picture is too.
        for (key in SIZE_PREVIEW_KEYS) {
            findPreference<SizePreviewPreference>(key)?.show(spec(landscape = key == LANDSCAPE_PREVIEW_KEY))
        }
    }

    /** The alpha layout as the service builds it, for [config]'s orientation (its row spread). */
    private fun previewKeys(ctx: Context, config: KeyboardConfig): List<Key> =
        try {
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

    // ------------------------------------------------------------------ orientation groups

    /** Each orientation's header opens and closes its rows; [SizeGroups] says which starts open. */
    private fun wireSizeGroups() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        for ((headerKey, rowsKey) in SizeGroups.GROUPS) {
            val header = findPreference<Preference>(headerKey) ?: continue
            val rows = findPreference<PreferenceCategory>(rowsKey) ?: continue
            setGroupOpen(header, rows, SizeGroups.startsOpen(rowsKey, landscape))
            header.setOnPreferenceClickListener {
                setGroupOpen(header, rows, !rows.isVisible)
                true
            }
        }
    }

    private fun setGroupOpen(header: Preference, rows: PreferenceCategory, open: Boolean) {
        rows.isVisible = open
        header.setIcon(if (open) R.drawable.ic_group_open else R.drawable.ic_group_closed)
    }

    /**
     * The day and night looks' groups, as the orientations' above: the look shown now starts open;
     * the night group is there only while the switch is on.
     */
    private fun wireLookGroups() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val nightShown = LookSets.nightDrawn(prefs, LookSets.systemNight(resources.configuration.uiMode))
        for ((headerKey, rowsKey) in LookGroups.GROUPS) {
            val header = findPreference<Preference>(headerKey) ?: continue
            val rows = findPreference<PreferenceCategory>(rowsKey) ?: continue
            setGroupOpen(header, rows, LookGroups.startsOpen(rowsKey, nightShown))
            header.setOnPreferenceClickListener {
                setGroupOpen(header, rows, !rows.isVisible)
                true
            }
        }
        showLookSets(prefs.getBoolean(Prefs.LOOK_SETS, Prefs.DEFAULT_LOOK_SETS))
    }

    /** With one look its group is named for it and stays open; the night group goes. */
    private fun showLookSets(on: Boolean) {
        val day = findPreference<Preference>(LookGroups.DAY_HEADER) ?: return
        day.setTitle(if (on) R.string.pref_look_day_title else R.string.pref_look_one_title)
        day.setSummary(if (on) R.string.pref_look_day_summary else R.string.pref_look_one_summary)
        findPreference<Preference>(LookGroups.NIGHT_HEADER)?.isVisible = on
        if (on) return
        findPreference<PreferenceCategory>(LookGroups.NIGHT_ROWS)?.isVisible = false
        findPreference<PreferenceCategory>(LookGroups.DAY_ROWS)?.let { setGroupOpen(day, it, true) }
    }

    /** A search result inside a closed group opens it first, or there is no row to scroll to. */
    private fun openGroupOf(key: String) {
        val parent = findPreference<Preference>(key)?.parent ?: return
        for ((headerKey, rowsKey) in SizeGroups.GROUPS + LookGroups.GROUPS) {
            if (parent.key != rowsKey || parent.isVisible) continue
            // The night group while the switch is off: no header to open it under.
            val header = findPreference<Preference>(headerKey)?.takeIf { it.isVisible } ?: continue
            setGroupOpen(header, parent as? PreferenceCategory ?: continue, true)
        }
    }

    // ------------------------------------------------------------------ changed settings

    /** Each row's default as it reported it, while the changed screen inflated the tree. */
    private val defaultsSeen = DefaultsRecorder()

    private val changedRows = ArrayList<Preference>()

    /**
     * One row per setting that differs from its default, read from the store each time the
     * screen shows, so a reset or a change made elsewhere is already reflected. The rows the
     * inflation built hold defaults, so only their titles and places are read.
     */
    private fun refreshChangedRows() {
        if (preferenceScreen?.key != CHANGED_SCREEN) return
        val ctx = context ?: return
        val screen = preferenceScreen ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val changed = ChangedSettings.changed(wholeTree(), withXmlDefaults(ctx), prefs.all)
        changedRows.forEach { screen.removePreference(it) }
        changedRows.clear()
        for ((i, entry) in changed.withIndex()) {
            val row = Preference(ctx).apply {
                order = i + 1
                title = entry.title
                summary = getString(R.string.pref_changed_in, entry.screenTitle)
                isPersistent = false
                isSingleLineTitle = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    offerReset(entry)
                    true
                }
            }
            screen.addPreference(row)
            changedRows.add(row)
        }
        findPreference<Preference>(CHANGED_NOTE_KEY)?.setSummary(
            if (changed.isEmpty()) R.string.pref_changed_none else R.string.pref_changed_note_summary,
        )
        val resettable = changed.filter { ChangedSettings.resettable(it.key) }
        findPreference<Preference>(CHANGED_RESET_ALL_KEY)?.apply {
            order = Int.MAX_VALUE - 1
            isEnabled = resettable.isNotEmpty()
            setOnPreferenceClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_changed_reset_all_title)
                    .setMessage(resources.getQuantityString(R.plurals.pref_changed_reset_all_message, resettable.size, resettable.size))
                    .setPositiveButton(R.string.pref_changed_reset) { _, _ -> reset(resettable.map { it.key }) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }

    /** Open the setting where it lives, or put it back to its default. */
    private fun offerReset(entry: SettingsIndex.Entry) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(entry.title)
            .setNeutralButton(R.string.pref_changed_open) { _, _ ->
                (activity as? SettingsActivity)?.revealSetting(entry.screenKey, entry.key)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (ChangedSettings.resettable(entry.key)) {
            dialog.setPositiveButton(R.string.pref_changed_reset) { _, _ -> reset(listOf(entry.key)) }
        } else {
            dialog.setMessage(R.string.pref_changed_managed)
        }
        dialog.show()
    }

    /** Writes each key's default in one edit, or removes the key when its default is empty. */
    private fun reset(keys: List<String>) {
        val ctx = context ?: return
        val defaults = withXmlDefaults(ctx)
        val edit = PreferenceManager.getDefaultSharedPreferences(ctx).edit()
        for (key in keys) {
            when (val d = ChangedSettings.defaultOf(key, defaults)) {
                is Boolean -> edit.putBoolean(key, d)
                is Int -> edit.putInt(key, d)
                is Long -> edit.putLong(key, d)
                is Float -> edit.putFloat(key, d)
                is String -> edit.putString(key, d)
                is Set<*> -> edit.putStringSet(key, d.filterIsInstance<String>().toSet())
                else -> edit.remove(key)
            }
        }
        edit.apply()
        refreshChangedRows()
    }

    /** The whole settings tree, reached from this screen's root: its rows, titles and screens. */
    private fun wholeTree(): List<SettingsIndex.Entry> {
        var top: PreferenceGroup = preferenceScreen ?: return emptyList()
        while (true) top = top.parent ?: break
        val out = ArrayList<SettingsIndex.Entry>()
        collectEntries(top, null, getString(R.string.settings_title), out)
        return out
    }

    /**
     * The recorded defaults of the rows whose XML gives one. A row with none reports its class's
     * fallback (false, 0, empty), which is not a default the keyboard reads.
     */
    private fun withXmlDefaults(ctx: Context): Map<String, Any?> {
        val keys = HashSet<String>()
        val parser = ctx.resources.getXml(R.xml.keyboard_prefs)
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val key = parser.getAttributeValue(ANDROID_NS, "key") ?: continue
                if (parser.getAttributeValue(ANDROID_NS, "defaultValue") != null) keys.add(key)
            }
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "settings defaults", e)
        } catch (e: IOException) {
            Log.w(TAG, "settings defaults", e)
        } finally {
            parser.close()
        }
        return defaultsSeen.seen.filterKeys { it in keys }
    }

    // ------------------------------------------------------------------ symbol pages

    /** Each symbol page's row opens its editor and says whether its rows are edited. */
    private fun wireSymbolRows() {
        val prefs = preferenceManager.sharedPreferences ?: return
        for (page in 1..2) {
            val row = findPreference<Preference>("pref_symbol_rows_$page") ?: continue
            val ctx = row.context
            row.summary = SymbolRowsDialog.summary(ctx, prefs, page)
            row.setOnPreferenceClickListener {
                SymbolRowsDialog.show(ctx, prefs, page) { row.summary = SymbolRowsDialog.summary(ctx, prefs, page) }
                true
            }
        }
    }

    // ------------------------------------------------------------------ search

    /**
     * A search row on top of the home screen. The toolbar's magnifier alone went unnoticed:
     * a row that says what it does is found. Built here without a key, so the index skips it and
     * the tree's row count never sees it.
     */
    private fun addSearchRow() {
        val ctx = context ?: return
        preferenceScreen?.addPreference(
            Preference(ctx).apply {
                order = -1
                title = getString(R.string.pref_home_search_title)
                summary = getString(R.string.pref_home_search_summary)
                setIcon(R.drawable.ic_settings_search)
                isPersistent = false
                isSingleLineTitle = false
                setOnPreferenceClickListener {
                    (activity as? SettingsActivity)?.openSearch()
                    true
                }
            },
        )
    }

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
            // The night look's rows and header count only while it is on; its rows are named by it.
            if ((key == LookGroups.NIGHT_HEADER || key == LookGroups.NIGHT_ROWS) && !lookSetsOn()) continue
            // A category is a header over rows, not a place to land: its rows are the results.
            if (child is PreferenceCategory) {
                val within = if (key == LookGroups.NIGHT_ROWS) getString(R.string.pref_look_night_title) else screenTitle
                collectEntries(child, screenKey, within, out)
                continue
            }
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

    private fun lookSetsOn(): Boolean =
        preferenceManager.sharedPreferences?.getBoolean(Prefs.LOOK_SETS, Prefs.DEFAULT_LOOK_SETS) == true

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
        openGroupOf(key)
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
        // The names come from the code, so the help can never list fewer than work.
        val note = getString(R.string.pref_letter_alternates_note_summary) + ". " +
            getString(R.string.pref_letter_alternates_names, ActionRow.listTokenNames())
        findPreference<Preference>(LETTER_NOTE_KEY)?.summary = note
        val maps = perLanguage.map { it.second }
        val defaults = LetterDefaults.unionPerLetter(maps, KeyboardConfig.MAX_LETTER_ALTERNATES)
        val rows = ArrayList<EditTextPreference>()
        // A letter's built-in list is the first enabled language's that has the letter, the
        // active one first, so a Latin row still has one with a Cyrillic board active.
        fun builtInFor(c: Char): List<String> = maps.firstOrNull { c in it }?.get(c).orEmpty()
        for (c in 'a'..'z') rows.add(addLetterRow(screen, c, builtInFor(c), defaults[c].orEmpty(), note))
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
            for (c in letters) rows.add(addLetterRow(group, c, builtInFor(c), defaults[c].orEmpty(), note))
        }
        // The numbers row's digits, empty until a list is written; used while the row is on.
        val digits = PreferenceCategory(ctx).apply {
            title = getString(R.string.pref_number_row_lists_title)
            isIconSpaceReserved = false
        }
        screen.addPreference(digits)
        for (c in KeyboardConfig.LIST_DIGITS) rows.add(addLetterRow(digits, c, emptyList(), emptyList(), note))
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
        note: String,
    ): EditTextPreference = addListRow(
        group, Prefs.letterAlternatesKey(c), c.uppercaseChar().toString(), own, default,
        getString(R.string.pref_letter_alternates_default), note,
    )

    /**
     * One symbol per row below the symbol pages' editors, the two pages as they are now,
     * typed rows included; a symbol on both pages once. Each shows its built-in list and opens
     * pre-filled with it. Built in code because the pages are the user's; a page that cannot be
     * read leaves its rows out instead of taking the screen down.
     */
    private fun addSymbolRows() {
        val screen = preferenceScreen ?: return
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val config = KeyboardConfig.from(prefs)
        val note = getString(R.string.pref_symbol_alternates_note_summary) + ". " +
            getString(R.string.pref_letter_alternates_names, ActionRow.listTokenNames())
        findPreference<Preference>(SYMBOL_NOTE_KEY)?.summary = note
        val shown = HashSet<String>()
        val rows = ArrayList<EditTextPreference>()
        for (page in 1..2) {
            val name = if (page == 2) KeyboardViews.SYMBOLS2 else KeyboardViews.SYMBOLS
            val built = try {
                SymbolRows.apply(LayoutLoader.load(ctx.assets, "layouts/$name.json"), page, config.symbolRows(page))
            } catch (e: IOException) {
                Log.w(TAG, "symbol page $name", e)
                continue
            } catch (e: JSONException) {
                Log.w(TAG, "symbol page $name", e)
                continue
            }
            val group = PreferenceCategory(ctx).apply {
                title = getString(if (page == 2) R.string.pref_symbol_alternates_page2_title else R.string.pref_symbol_alternates_page1_title)
                isIconSpaceReserved = false
            }
            screen.addPreference(group)
            for (symbol in SymbolRows.symbolsOf(built)) {
                if (!shown.add(symbol)) continue
                val own = built.keys.firstOrNull { it.output == symbol }?.alternates.orEmpty()
                rows.add(
                    addListRow(group, Prefs.symbolAlternatesKey(symbol), symbol, own, own,
                        getString(R.string.pref_symbol_alternates_builtin_button), note),
                )
            }
        }
        // Last, below the lists; every list goes, those of symbols no longer on a page too.
        val reset = findPreference<Preference>(SYMBOL_RESET_KEY) ?: return
        screen.removePreference(reset)
        screen.addPreference(reset)
        reset.setOnPreferenceClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.pref_letter_alternates_reset_title)
                .setMessage(R.string.pref_symbol_alternates_reset_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    rows.forEach { it.text = null }
                    val e = prefs.edit()
                    for (k in prefs.all.keys) if (Prefs.symbolOfAlternatesKey(k) != null) e.remove(k)
                    e.apply()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    /**
     * One list's row: its built-in list as the summary and the starting text, and a button that
     * fills in [default]. The built-in list typed back, or nothing, is stored as no list.
     */
    private fun addListRow(
        group: PreferenceGroup,
        key: String,
        shownAs: String,
        own: List<String>,
        default: List<String>,
        defaultLabel: String,
        note: String,
    ): EditTextPreference {
        val ownText = own.joinToString(" ")
        val letter = shownAs
        val row = EditTextPreference(group.context)
        row.key = key
        row.title = letter
        row.isSingleLineTitle = false
        row.dialogTitle = getString(R.string.pref_letter_alternates_dialog_title, letter)
        row.dialogMessage = note
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
                        text = defaultLabel
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
     * The icon gutter only beside rows that have an icon: the home screen's. Elsewhere it would
     * squeeze the description column on a 21:9 phone for nothing.
     *
     * Walked in code, not set per element in the XML: a new row would arrive without the
     * attribute, and this also reaches the categories and the theme preview. A group whose rows
     * have icons keeps the gutter itself, so its header lines up with their titles.
     */
    private fun dropIconSpace(group: PreferenceGroup) {
        val gutter = (0 until group.preferenceCount).any { group.getPreference(it).icon != null }
        group.isIconSpaceReserved = gutter
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            if (child is PreferenceGroup) dropIconSpace(child) else child.isIconSpaceReserved = gutter
        }
    }

    /**
     * The version of the build that is running.
     *
     * Read from the installed package, not a compile-time constant, so the line says which APK is
     * on this phone. The debug build reports its `-dev` suffix, so the row also tells the two
     * installed apps apart.
     */
    private fun showVersion() {
        val pref = findPreference<Preference>(VERSION_KEY) ?: return
        val ctx = context ?: return
        pref.summary = versionText(ctx)
    }

    private fun versionText(ctx: Context): String =
        try {
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

    /**
     * The home rows say what is set, not only what is inside: the languages in use, the voice
     * keyboard the voice key opens, the version. Home only: a subscreen and the setup have a key.
     */
    private fun refreshHomeSummaries() {
        if (preferenceScreen?.key != null) return
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val config = KeyboardConfig.from(prefs)
        val names = resources.getStringArray(R.array.language_entries)
        val codes = resources.getStringArray(R.array.language_values)
        val shown = (listOf(config.language) + config.enabledLanguages).distinct()
            .mapNotNull { names.getOrNull(codes.indexOf(it)) }
        if (shown.isNotEmpty()) findPreference<Preference>(LANGUAGES_KEY)?.summary = shown.joinToString(", ")
        findPreference<Preference>(DictationHandoff.SCREEN)?.summary = DictationHandoff.find(ctx)?.let {
            getString(R.string.pref_home_voice_found, it.info.loadLabel(ctx.packageManager))
        } ?: getString(R.string.pref_home_voice_none)
        findPreference<Preference>(HELP_KEY)?.summary = getString(R.string.pref_home_help_version, versionText(ctx))
    }

    /**
     * The link opens in the browser, so the app keeps no network permission. With no browser the
     * row shows the address instead.
     */
    private fun wireDictation() {
        for ((key, url) in listOf(
            DICTATION_GET_KEY to DictationHandoff.SUGGESTED_URL,
            DICTATION_GET_FUTO_KEY to DictationHandoff.FUTO_URL,
        )) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { row ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: ActivityNotFoundException) {
                    row.summary = url
                }
                true
            }
        }
    }

    /** Names the voice keyboard the voice key opens, read from the phone each time. */
    private fun refreshDictationStatus() {
        val pref = findPreference<Preference>(DICTATION_STATUS_KEY) ?: return
        val ctx = context ?: return
        val found = DictationHandoff.find(ctx)
        pref.summary = if (found == null) {
            getString(R.string.pref_dictation_status_none)
        } else {
            getString(R.string.pref_dictation_status_found, found.info.loadLabel(ctx.packageManager))
        }
    }

    /**
     * Keeps the palette swatch under the colour in step with the preferences that decide it. The change listeners take the new value from the callback: onPreferenceChange runs
     * before the value is persisted, so reading preferences here would show the previous palette.
     * The night look's screen has the same rows under the night keys, and its own preview.
     */
    private fun wireThemePreview(night: Boolean) {
        fun k(key: String) = if (night) Prefs.nightTwin(key) else key
        val preview = findPreference<ThemePreviewPreference>(k(PREVIEW_KEY)) ?: return
        val colour = findPreference<ColorHexPreference>(k(Prefs.THEME_COLOR_HEX))
        val mode = findPreference<ListPreference>(k(Prefs.THEME_MODE))
        val brightness = findPreference<ListPreference>(k(Prefs.THEME_BRIGHTNESS))
        val borders = findPreference<TwoStatePreference>(k(Prefs.KEY_BORDERS))
        val borderWidth = findPreference<SeekBarPreference>(k(Prefs.KEY_BORDER_WIDTH))

        fun refresh(
            newColour: String? = null,
            newMode: String? = null,
            newBrightness: String? = null,
            newBorders: Boolean? = null,
            newBorderWidth: Int? = null,
        ) {
            val prefs = preferenceManager.sharedPreferences ?: return
            // KeyboardConfig owns the default, so going through it keeps the swatch correct on
            // first open, before a colour is typed.
            val config = KeyboardConfig.from(LookSets.view(prefs, night))
            val primary = HexColor.colorOr(newColour, config.themeColor)
            val resolvedMode = newMode ?: config.themeMode
            val bordersOn = newBorders ?: (borders?.isChecked ?: Prefs.DEFAULT_KEY_BORDERS)
            // The setup's theme step has no border rows: the saved borders, not the default.
            val borderDp = when {
                borders == null -> config.keyBorderDp
                bordersOn -> newBorderWidth ?: borderWidth?.value ?: Prefs.DEFAULT_KEY_BORDER_WIDTH
                else -> 0
            }
            preview.show(
                KeyboardTheme.resolve(
                    requireContext(),
                    resolvedMode,
                    primary,
                    newBrightness ?: config.themeBrightness,
                ),
                getString(
                    if (KeyboardTheme.colourAffects(resolvedMode)) {
                        R.string.theme_preview_note_custom
                    } else {
                        R.string.theme_preview_note_fixed
                    },
                ),
                borderDp,
            )
        }

        colour?.setOnPreferenceChangeListener { _, value ->
            refresh(newColour = value as? String)
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
        // A row that does nothing in the look as set is hidden, not greyed: the width without
        // borders, the typed trail colour unless the trail is Custom.
        val trail = findPreference<ListPreference>(k(Prefs.TRAIL_COLOR))
        val trailHex = findPreference<Preference>(k(Prefs.TRAIL_COLOR_HEX))
        borders?.setOnPreferenceChangeListener { _, value ->
            refresh(newBorders = value as? Boolean)
            borderWidth?.isVisible = value == true
            true
        }
        borderWidth?.setOnPreferenceChangeListener { _, value ->
            refresh(newBorderWidth = value as? Int)
            true
        }
        trail?.setOnPreferenceChangeListener { _, value ->
            trailHex?.isVisible = value == CUSTOM_TRAIL
            true
        }
        borderWidth?.isVisible = borders?.isChecked == true
        trailHex?.isVisible = trail?.value == CUSTOM_TRAIL
        refresh()
    }

    // ------------------------------------------------------------------ background image

    /** Which look's image the open picker is for. */
    private var pickingNight = false

    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBackdrop(uri, pickingNight)
        }

    /** The image row picks a picture, or offers another or none once one is set. */
    private fun wireBackdrop(night: Boolean) {
        val row = findPreference<Preference>(if (night) BACKDROP_ROW_NIGHT_KEY else BACKDROP_ROW_KEY) ?: return
        row.setOnPreferenceClickListener {
            val ctx = requireContext()
            val prefs = preferenceManager.sharedPreferences
            pickingNight = night
            if (prefs != null && BackdropStore.isSet(ctx, prefs, night)) {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.backdrop_title)
                    .setItems(
                        arrayOf(
                            getString(R.string.backdrop_change), getString(R.string.backdrop_adjust),
                            getString(R.string.backdrop_remove),
                        ),
                    ) { _, which ->
                        when (which) {
                            0 -> pickBackdrop.launch(arrayOf("image/*"))
                            1 -> startActivity(
                                Intent(ctx, BackdropCropActivity::class.java).putExtra(BackdropCropActivity.EXTRA_NIGHT, night),
                            )
                            else -> removeBackdrop(night)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                pickBackdrop.launch(arrayOf("image/*"))
            }
            true
        }
        refreshBackdropRow(night)
    }

    /** The row says whether an image is set; its two opacities only act while one is. */
    private fun refreshBackdropRow(night: Boolean) {
        fun k(key: String) = if (night) Prefs.nightTwin(key) else key
        val row = findPreference<Preference>(if (night) BACKDROP_ROW_NIGHT_KEY else BACKDROP_ROW_KEY) ?: return
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val set = BackdropStore.isSet(ctx, prefs, night)
        row.setSummary(if (set) R.string.backdrop_set else R.string.backdrop_none)
        findPreference<Preference>(k(Prefs.BACKDROP_ALPHA))?.isVisible = set
        findPreference<Preference>(k(Prefs.KEY_ALPHA))?.isVisible = set
    }

    /**
     * Copies the picked image in off the main thread, then opens the crop screen on it; that
     * screen's Save makes it the image and writes the stamp the keyboard watches.
     */
    private fun importBackdrop(uri: Uri, night: Boolean) {
        val app = requireContext().applicationContext
        val dm = resources.displayMetrics
        val maxLong = maxOf(dm.widthPixels, dm.heightPixels)
        val main = Handler(Looper.getMainLooper())
        Thread({
            val ok = try {
                BackdropStore.import(app, uri, maxLong)
                true
            } catch (e: IOException) {
                Log.w(TAG, "background image not imported", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "background image not readable", e)
                false
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "background image too large", e)
                false
            }
            main.post {
                val host = activity
                when {
                    !ok -> Toast.makeText(app, R.string.backdrop_failed, Toast.LENGTH_LONG).show()
                    // The settings closed while the copy ran: nothing to fit it in, so it goes.
                    host == null -> BackdropStore.dropPending(app)
                    else -> host.startActivity(
                        Intent(host, BackdropCropActivity::class.java)
                            .putExtra(BackdropCropActivity.EXTRA_PENDING, true)
                            .putExtra(BackdropCropActivity.EXTRA_NIGHT, night),
                    )
                }
                refreshBackdropRow(night)
            }
        }, "kinetica-backdrop-import").start()
    }

    private fun removeBackdrop(night: Boolean) {
        fun k(key: String) = if (night) Prefs.nightTwin(key) else key
        val ctx = requireContext()
        BackdropStore.remove(ctx, night)
        preferenceManager.sharedPreferences?.edit()
            ?.putString(k(Prefs.BACKDROP), "")
            ?.remove(k(Prefs.BACKDROP_CROP))
            ?.remove(k(Prefs.BACKDROP_CROP_LANDSCAPE))
            ?.apply()
        refreshBackdropRow(night)
    }

    /**
     * The first time the day and night looks are turned on, the night look starts as a copy of
     * the day look, image included, so turning it on changes nothing until it is edited.
     */
    private fun wireLookSets() {
        findPreference<TwoStatePreference>(Prefs.LOOK_SETS)?.setOnPreferenceChangeListener { _, value ->
            val prefs = preferenceManager.sharedPreferences
            if (value == true && prefs != null && !prefs.getBoolean(Prefs.LOOK_NIGHT_SEEDED, false)) {
                // The file first: the copied stamp then points at an image that is there.
                BackdropStore.copyDayToNight(requireContext())
                LookSets.seed(prefs)
                // The rows follow the copies through rowSync; the image row is not a pref.
                refreshBackdropRow(night = true)
            }
            showLookSets(value == true)
            true
        }
    }

    private var revealKey: String? = null

    companion object {
        /** The XML to inflate instead of the settings tree; the setup's [forSetup]. */
        const val ARG_XML = "com.kinetica.keyboard.settings.XML"

        /** The row a screen is opened for, set by [SettingsActivity.revealSetting]. */
        const val ARG_REVEAL = "com.kinetica.keyboard.settings.REVEAL_ROW"

        /** One step of the guided setup: [screenKey] of the setup XML, wired as the settings are. */
        fun forSetup(screenKey: String): KeyboardPrefsFragment = KeyboardPrefsFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_XML, R.xml.setup_prefs)
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, screenKey)
            }
        }

        private const val TAG = "KineticaPrefs"
        private const val RESET_LETTER_LISTS_KEY = "pref_letter_alternates_reset"
        private const val LETTER_NOTE_KEY = "pref_letter_alternates_note"
        private const val SYMBOL_NOTE_KEY = "pref_symbol_alternates_note"
        private const val SYMBOL_RESET_KEY = "pref_symbol_alternates_reset"
        private const val PREVIEW_KEY = "pref_theme_preview"
        private const val BACKDROP_ROW_KEY = "pref_backdrop_image"
        private const val BACKDROP_ROW_NIGHT_KEY = "pref_backdrop_image_night"
        private const val CUSTOM_TRAIL = "custom"
        private const val LANDSCAPE_PREVIEW_KEY = "pref_size_preview_landscape"
        private val SIZE_PREVIEW_KEYS = listOf("pref_size_preview", LANDSCAPE_PREVIEW_KEY, "pref_bar_preview")
        private val SIZE_SLIDERS = listOf(
            Prefs.KEYBOARD_HEIGHT_PCT, Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE, Prefs.SUGGESTION_BAR_DP,
            Prefs.SIDE_PAD_DP, Prefs.BOTTOM_PAD_DP, Prefs.DRAG_HANDLE_DP, Prefs.LANDSCAPE_SPLIT_GAP_PCT,
            Prefs.HOME_ROW_SPREAD_PCT,
        ) + Prefs.LANDSCAPE_TWINS.map(Prefs::landscapeTwin)
        private const val VERSION_KEY = "pref_version"
        private const val CHANGED_SCREEN = "pref_group_changed"
        private const val CHANGED_NOTE_KEY = "pref_changed_note"
        private const val CHANGED_RESET_ALL_KEY = "pref_changed_reset_all"
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val LANGUAGES_KEY = "pref_group_languages"
        private const val HELP_KEY = "pref_group_learn"
        private const val DICTATION_STATUS_KEY = "pref_dictation_status"
        private const val DICTATION_GET_KEY = "pref_dictation_get"
        private const val DICTATION_GET_FUTO_KEY = "pref_dictation_get_futo"
        // Visible enough to find by eye, faint enough not to read as a selection, and long
        // enough to still be fading when the scroll settles.
        private const val FLASH_ALPHA = 90
        private const val FLASH_MS = 900L
    }
}
