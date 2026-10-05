package com.kinetica.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.kinetica.keyboard.R

/**
 * Hosts the preference screen, its submenus and the search over them.
 *
 * The settings are nested screens, three levels at most, and nesting needs this callback: without
 * it androidx routes a subscreen tap to nothing and the row looks dead.
 *
 * Each subscreen is the same fragment re-inflated with a root key, so the tree lives in one XML.
 * The fragment sets the toolbar title from its own root, so the title stays right after a
 * rotation and after Back.
 *
 * Nesting hides rows, so there is a search field. It is on the top level only, the fragment
 * with the whole tree inflated to read.
 */
class SettingsActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    private var results: ListView? = null
    private var shown: List<SettingsIndex.Entry> = emptyList()
    private var searchItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, KeyboardPrefsFragment())
                .commit()
            // The keyboard can ask for one screen and one row: a letter's long-press list (#8).
            // The activity is exported, so only a screen this app has is opened.
            intent?.getStringExtra(EXTRA_SCREEN)?.takeIf { it in OPENABLE_SCREENS }?.let { screen ->
                supportFragmentManager.executePendingTransactions()
                openScreen(screen, intent?.getStringExtra(EXTRA_REVEAL))
            }
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Search belongs to the top level, so the item has to come and go with the stack.
        supportFragmentManager.addOnBackStackChangedListener {
            closeSearch()
            invalidateOptionsMenu()
        }
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        openScreen(pref.key, revealKey = null)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        // Up leaves a subscreen before it leaves the activity.
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }

    // ---------------------------------------------------------------------- search

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings, menu)
        val item = menu.findItem(R.id.action_search)
        searchItem = item
        val view = item.actionView as? SearchView ?: return true
        view.queryHint = getString(R.string.settings_search_hint)
        view.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean = true

                override fun onQueryTextChange(newText: String): Boolean {
                    showMatches(newText)
                    return true
                }
            },
        )
        item.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    hideResults()
                    return true
                }
            },
        )
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_search)?.isVisible =
            supportFragmentManager.backStackEntryCount == 0
        return super.onPrepareOptionsMenu(menu)
    }

    private fun topFragment(): KeyboardPrefsFragment? =
        supportFragmentManager.findFragmentById(android.R.id.content) as? KeyboardPrefsFragment

    /**
     * The whole searchable tree: every inflated row, plus the chord-screen settings that have no
     * row of their own.
     */
    private fun entries(): List<SettingsIndex.Entry> {
        val walked = topFragment()?.searchEntries() ?: emptyList()
        return walked + EXTRA_ROWS.map { (key, titles) ->
            SettingsIndex.Entry(
                key = key,
                title = getString(titles.first),
                summary = getString(titles.second),
                screenKey = CHORD_SCREEN,
                screenTitle = getString(R.string.chord_settings_title),
                terms = SettingsSynonyms.termsFor(key),
            )
        }
    }

    private fun showMatches(query: String) {
        shown = SettingsIndex.match(entries(), query)
        if (query.isBlank()) {
            hideResults()
            return
        }
        val list = resultsView()
        val blank = empty ?: return
        // A fragment replace appends its view after the overlay, so after opening a result and
        // coming Back the settings list would sit on top and both would draw.
        blank.bringToFront()
        list.bringToFront()
        if (shown.isEmpty()) {
            // The list is drawn over the note and has its own background, so an empty list
            // would hide the note.
            list.visibility = View.GONE
            blank.visibility = View.VISIBLE
            return
        }
        list.adapter = SimpleAdapter(
            this,
            shown.map { mapOf(ROW_TITLE to it.title, ROW_SCREEN to it.screenTitle) },
            android.R.layout.simple_list_item_2,
            arrayOf(ROW_TITLE, ROW_SCREEN),
            intArrayOf(android.R.id.text1, android.R.id.text2),
        )
        blank.visibility = View.GONE
        list.visibility = View.VISIBLE
    }

    private fun hideResults() {
        results?.visibility = View.GONE
        empty?.visibility = View.GONE
        shown = emptyList()
    }

    private fun closeSearch() {
        searchItem?.takeIf { it.isActionViewExpanded }?.collapseActionView()
        hideResults()
    }

    /**
     * Opens the result's own screen and asks for its row.
     *
     * A subscreen result commits the same transaction a tap on that screen would, then asks the
     * new fragment for the row; the fragment holds the request until it has a list to scroll, so
     * the order of the two does not matter. A top-level result pops back to the existing
     * fragment and asks it directly. Results are addressed by `android:key`, never by list
     * position (see [PersonalWordRows.checkedPositions]).
     */
    private fun openResult(entry: SettingsIndex.Entry) {
        closeSearch()
        revealSetting(entry.screenKey, entry.key)
    }

    /** Opens [screenKey] (null: the top level) and flashes its row [key]; a search hit or a tip. */
    fun revealSetting(screenKey: String?, key: String) {
        if (screenKey == null) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportFragmentManager.executePendingTransactions()
            topFragment()?.revealPreference(key)
            return
        }
        if (screenKey == CHORD_SCREEN) {
            startActivity(Intent(this, ChordSettingsActivity::class.java))
            return
        }
        openScreen(screenKey, revealKey = key)
    }

    private fun openScreen(screenKey: String?, revealKey: String?) {
        val fragment = KeyboardPrefsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, screenKey)
            }
        }
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(screenKey)
            .commit()
        if (revealKey != null) {
            supportFragmentManager.executePendingTransactions()
            fragment.revealPreference(revealKey)
        }
    }

    // ---- the results overlay, built in code like every other screen in this package ----

    private var empty: TextView? = null

    private fun resultsView(): ListView {
        results?.let { return it }
        val list = ListView(this).apply {
            setBackgroundColor(windowBackground())
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            setOnItemClickListener { _, _, at, _ -> shown.getOrNull(at)?.let { openResult(it) } }
        }
        val blank = TextView(this).apply {
            text = getString(R.string.settings_search_none)
            setBackgroundColor(windowBackground())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addContentView(blank, params)
        addContentView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        results = list
        empty = blank
        return list
    }

    private fun windowBackground(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }

    internal companion object {
        const val ROW_TITLE = "title"
        const val ROW_SCREEN = "screen"

        /** The screen key a chord-screen result navigates to. */
        const val CHORD_SCREEN = "pref_chords"

        /** A screen key to open on arrival, and a row in it to reveal. */
        const val EXTRA_SCREEN = "com.kinetica.keyboard.settings.SCREEN"
        const val EXTRA_REVEAL = "com.kinetica.keyboard.settings.REVEAL"

        /** Screens the keyboard may ask for by extra. */
        val OPENABLE_SCREENS = setOf(SettingsSynonyms.LONGPRESS_GROUP)

        /**
         * Title and summary for the settings inside [ChordSettingsActivity], which appear in no
         * preference XML; without them search cannot find the chord lead-ins.
         */
        val EXTRA_ROWS: List<Pair<String, Pair<Int, Int>>> = listOf(
            Prefs.CHORD_ARM_MS to
                (R.string.pref_chord_arm_title to R.string.pref_chord_arm_summary),
            Prefs.SPACE_CHORD_ARM_MS to
                (R.string.pref_space_chord_arm_title to R.string.pref_space_chord_arm_summary),
        )
    }
}
