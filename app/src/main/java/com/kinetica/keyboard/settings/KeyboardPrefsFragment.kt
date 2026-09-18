package com.kinetica.keyboard.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.recyclerview.widget.RecyclerView
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.KeyboardTheme

class KeyboardPrefsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.keyboard_prefs, rootKey)
        dropIconSpace(preferenceScreen)
        wireThemePreview()
        showVersion()
    }

    /**
     * Names the screen the user is actually on.
     *
     * Taken from the inflated root rather than tracked in the activity, so it survives a
     * rotation and a Back press without a second copy of the titles existing anywhere.
     */
    override fun onResume() {
        super.onResume()
        activity?.title = preferenceScreen?.title ?: getString(R.string.settings_title)
        // A search result asks for its row before this fragment has a list to scroll, so
        // the request is held and spent here, once, whichever way it arrived.
        applyReveal()
    }

    // ------------------------------------------------------------------ search (R82)

    /**
     * Every row of the whole tree, for [SettingsIndex].
     *
     * Read off the INFLATED screen rather than parsed out of the XML, which buys three
     * things: the six rows whose summary is their selected list entry resolve themselves,
     * a row added to the XML is searchable with no second list to update, and there is no
     * stored index that can go stale - the request's "rebuilt on update or language change"
     * is answered by there being nothing to rebuild.
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
            // The theme swatch is the one row with no title, and a result with no words on
            // it is not a result.
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
     * The flash is on the row's FOREGROUND, so the preference keeps its own background and
     * its ripple: a search that repainted the row would leave it looking selected.
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
     * androidx maps a key to an adapter position, but `PreferenceGroupAdapter` is
     * `@RestrictTo` and using it is a lint ERROR, which this project keeps at zero. The row
     * has just been scrolled to, so it is among the visible children and its title is the
     * one thing a public API exposes about it. Two rows with the same title would flash the
     * upper one, which is the whole cost.
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
     * No preference on this screen has an icon, so none of them should reserve the gutter
     * one would sit in.
     *
     * Walked in code rather than set per element in the XML: there are 52 of them, a new
     * one would arrive without the attribute, and this also reaches the categories and the
     * theme preview. Reported by a user on a 21:9 phone, where the reserved space is wide
     * enough to squeeze the description column.
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
     * The version of the build that is actually running.
     *
     * Read from the installed package rather than from a compile-time constant, so
     * the line answers the question it exists for - which APK is on this phone -
     * rather than what some build once intended. The developer build reports its own
     * `-dev` suffix, so the row also tells the two installed apps apart.
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
     * Keeps the palette swatch under the hue slider in step with the three
     * preferences that decide it. The change listeners take the NEW value from the
     * callback rather than reading it back: onPreferenceChange runs before the
     * value is persisted, so reading preferences here would always show the
     * previous palette.
     */
    private fun wireThemePreview() {
        val preview = findPreference<ThemePreviewPreference>(PREVIEW_KEY) ?: return
        val hue = findPreference<SeekBarPreference>(Prefs.THEME_HUE)
        val mode = findPreference<ListPreference>(Prefs.THEME_MODE)
        val brightness = findPreference<ListPreference>(Prefs.THEME_BRIGHTNESS)

        // Repaint while the thumb is still moving, which is the whole point of a
        // preview; without this the swatch only catches up on release.
        hue?.updatesContinuously = true

        fun refresh(newHue: Int? = null, newMode: String? = null, newBrightness: String? = null) {
            val prefs = preferenceManager.sharedPreferences ?: return
            // KeyboardConfig owns the migration from the retired colour list, so
            // going through it is what makes the swatch correct on first open,
            // before the hue preference has ever been written.
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
        const val PREVIEW_KEY = "pref_theme_preview"
        const val VERSION_KEY = "pref_version"
        // Visible enough to find with your eye, faint enough not to read as a selection,
        // and long enough to still be fading when the scroll settles.
        const val FLASH_ALPHA = 90
        const val FLASH_MS = 900L
    }
}
