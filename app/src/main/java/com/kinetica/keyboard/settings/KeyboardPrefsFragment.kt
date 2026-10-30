package com.kinetica.keyboard.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.LandscapeArrangement
import com.kinetica.keyboard.layout.LayoutLoader
import com.kinetica.keyboard.layout.LayoutTransforms
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
        setPreferencesFromResource(R.xml.keyboard_prefs, rootKey)
        if (rootKey == SettingsSynonyms.LONGPRESS_GROUP) addLetterRows()
        dropIconSpace(preferenceScreen)
        wireThemePreview()
        wireBackdrop()
        wireSizePreview()
        wireSizeGroups()
        wireSymbolRows()
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
        refreshBackdropRow()
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
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val dm = resources.displayMetrics
        // Both orientations whichever way the phone is held: each has its own sizes, and
        // landscape its own arrangement, so split can be on in one and off in the other.
        val short = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val long = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        fun spec(landscape: Boolean): SizePreviewView.Spec {
            val config = KeyboardConfig.from(prefs, landscape)
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

    /** A search result inside a closed group opens it first, or there is no row to scroll to. */
    private fun openGroupOf(key: String) {
        val parent = findPreference<Preference>(key)?.parent ?: return
        for ((headerKey, rowsKey) in SizeGroups.GROUPS) {
            if (parent.key != rowsKey || parent.isVisible) continue
            val header = findPreference<Preference>(headerKey) ?: continue
            setGroupOpen(header, parent as? PreferenceCategory ?: continue, true)
        }
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
    ): EditTextPreference {
        val ownText = own.joinToString(" ")
        val letter = c.uppercaseChar().toString()
        val row = EditTextPreference(group.context)
        row.key = Prefs.letterAlternatesKey(c)
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
     * on this phone. The debug build reports its `-dev` suffix, so the row also tells the two
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
     * Keeps the palette swatch under the colour in step with the preferences that decide it. The change listeners take the new value from the callback: onPreferenceChange runs
     * before the value is persisted, so reading preferences here would show the previous palette.
     */
    private fun wireThemePreview() {
        val preview = findPreference<ThemePreviewPreference>(PREVIEW_KEY) ?: return
        val colour = findPreference<ColorHexPreference>(Prefs.THEME_COLOR_HEX)
        val mode = findPreference<ListPreference>(Prefs.THEME_MODE)
        val brightness = findPreference<ListPreference>(Prefs.THEME_BRIGHTNESS)
        val borders = findPreference<TwoStatePreference>(Prefs.KEY_BORDERS)
        val borderWidth = findPreference<SeekBarPreference>(Prefs.KEY_BORDER_WIDTH)

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
            val config = KeyboardConfig.from(prefs)
            val primary = HexColor.colorOr(newColour, config.themeColor)
            val resolvedMode = newMode ?: config.themeMode
            val bordersOn = newBorders ?: (borders?.isChecked ?: Prefs.DEFAULT_KEY_BORDERS)
            val borderDp = if (bordersOn) {
                newBorderWidth ?: borderWidth?.value ?: Prefs.DEFAULT_KEY_BORDER_WIDTH
            } else {
                0
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
        borders?.setOnPreferenceChangeListener { _, value ->
            refresh(newBorders = value as? Boolean)
            true
        }
        borderWidth?.setOnPreferenceChangeListener { _, value ->
            refresh(newBorderWidth = value as? Int)
            true
        }
        refresh()
    }

    // ------------------------------------------------------------------ background image

    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBackdrop(uri)
        }

    /** The image row picks a picture, or offers another or none once one is set. */
    private fun wireBackdrop() {
        val row = findPreference<Preference>(BACKDROP_ROW_KEY) ?: return
        row.setOnPreferenceClickListener {
            val ctx = requireContext()
            val prefs = preferenceManager.sharedPreferences
            if (prefs != null && BackdropStore.isSet(ctx, prefs)) {
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
                            1 -> startActivity(Intent(ctx, BackdropCropActivity::class.java))
                            else -> removeBackdrop()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                pickBackdrop.launch(arrayOf("image/*"))
            }
            true
        }
        refreshBackdropRow()
    }

    /** The row says whether an image is set; its two opacities only act while one is. */
    private fun refreshBackdropRow() {
        val row = findPreference<Preference>(BACKDROP_ROW_KEY) ?: return
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val set = BackdropStore.isSet(ctx, prefs)
        row.setSummary(if (set) R.string.backdrop_set else R.string.backdrop_none)
        findPreference<Preference>(Prefs.BACKDROP_ALPHA)?.isEnabled = set
        findPreference<Preference>(Prefs.KEY_ALPHA)?.isEnabled = set
    }

    /**
     * Copies the picked image in off the main thread, then opens the crop screen on it; that
     * screen's Save makes it the image and writes the stamp the keyboard watches.
     */
    private fun importBackdrop(uri: Uri) {
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
                        Intent(host, BackdropCropActivity::class.java).putExtra(BackdropCropActivity.EXTRA_PENDING, true),
                    )
                }
                refreshBackdropRow()
            }
        }, "kinetica-backdrop-import").start()
    }

    private fun removeBackdrop() {
        val ctx = requireContext()
        BackdropStore.remove(ctx)
        preferenceManager.sharedPreferences?.edit()
            ?.putString(Prefs.BACKDROP, "")
            ?.remove(Prefs.BACKDROP_CROP)
            ?.remove(Prefs.BACKDROP_CROP_LANDSCAPE)
            ?.apply()
        refreshBackdropRow()
    }

    private var revealKey: String? = null

    private companion object {
        const val TAG = "KineticaPrefs"
        const val RESET_LETTER_LISTS_KEY = "pref_letter_alternates_reset"
        const val LETTER_NOTE_KEY = "pref_letter_alternates_note"
        const val PREVIEW_KEY = "pref_theme_preview"
        const val BACKDROP_ROW_KEY = "pref_backdrop_image"
        const val LANDSCAPE_PREVIEW_KEY = "pref_size_preview_landscape"
        val SIZE_PREVIEW_KEYS = listOf("pref_size_preview", LANDSCAPE_PREVIEW_KEY, "pref_bar_preview")
        val SIZE_SLIDERS = listOf(
            Prefs.KEYBOARD_HEIGHT_PCT, Prefs.KEYBOARD_HEIGHT_PCT_LANDSCAPE, Prefs.SUGGESTION_BAR_DP,
            Prefs.SIDE_PAD_DP, Prefs.BOTTOM_PAD_DP, Prefs.DRAG_HANDLE_DP, Prefs.LANDSCAPE_SPLIT_GAP_PCT,
            Prefs.HOME_ROW_SPREAD_PCT,
        ) + Prefs.LANDSCAPE_TWINS.map(Prefs::landscapeTwin)
        const val VERSION_KEY = "pref_version"
        // Visible enough to find by eye, faint enough not to read as a selection, and long
        // enough to still be fading when the scroll settles.
        const val FLASH_ALPHA = 90
        const val FLASH_MS = 900L
    }
}
