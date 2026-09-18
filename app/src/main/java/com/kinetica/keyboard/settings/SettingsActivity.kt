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
 * The settings were one flat list of 52 rows, which a user reported as impossible to
 * navigate. They are five nested screens now, and nesting needs this callback: without it
 * androidx routes a subscreen tap to nothing at all and the row looks dead.
 *
 * Each subscreen is the same fragment re-inflated with a root key, so the tree lives in one
 * XML and nothing has to be kept in sync. The fragment sets the toolbar title from its own
 * root, which is what keeps it right after a rotation as well as after Back.
 *
 * Grouping then produced the opposite report - things were now hidden - which is R82 and
 * why there is a search field. It is offered on the top level only, because that is the
 * fragment that has the whole tree inflated to read.
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

    // ---------------------------------------------------------------------- search (R82)

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
     * The whole searchable tree: every inflated row, plus the three settings that have no
     * row of their own because they were gathered onto the chord screen.
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
        if (shown.isEmpty()) {
            // The list is drawn over the note and has its own background, so an empty list
            // would hide the note rather than sit above it.
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
     * A subscreen result commits the same transaction a tap on that screen would, then
     * asks the new fragment for the row; the fragment holds the request until it has a list
     * to scroll, so the order of the two does not matter. A top-level result pops back to a
     * fragment that already exists and asks it directly. Results are addressed by
     * `android:key` throughout - never by list position, which is the bug class
     * [PersonalWordRows.checkedPositions] is written to document.
     */
    private fun openResult(entry: SettingsIndex.Entry) {
        closeSearch()
        if (entry.screenKey == null) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportFragmentManager.executePendingTransactions()
            topFragment()?.revealPreference(entry.key)
            return
        }
        if (entry.screenKey == CHORD_SCREEN) {
            startActivity(Intent(this, ChordSettingsActivity::class.java))
            return
        }
        openScreen(entry.screenKey, revealKey = entry.key)
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

    private companion object {
        const val ROW_TITLE = "title"
        const val ROW_SCREEN = "screen"

        /** The screen key a chord-screen result navigates to. */
        const val CHORD_SCREEN = "pref_chords"

        /**
         * Title and summary for the settings that live inside [ChordSettingsActivity] and
         * therefore appear in no preference XML. Without these three, the only way to find
         * the chord lead-in is to already know it is on the chord screen.
         */
        val EXTRA_ROWS: List<Pair<String, Pair<Int, Int>>> = listOf(
            Prefs.CHORD_ARM_MS to
                (R.string.pref_chord_arm_title to R.string.pref_chord_arm_summary),
            Prefs.LANG_CYCLE_KEY to
                (R.string.pref_lang_cycle_key_title to R.string.pref_lang_cycle_key_summary),
            Prefs.PECK_CHORD_KEY to
                (R.string.pref_peck_chord_key_title to R.string.pref_peck_chord_key_summary),
        )
    }
}
