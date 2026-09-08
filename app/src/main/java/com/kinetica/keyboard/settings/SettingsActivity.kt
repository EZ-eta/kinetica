package com.kinetica.keyboard.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen

/**
 * Hosts the preference screen and its submenus.
 *
 * The settings were one flat list of 52 rows, which a user reported as impossible to
 * navigate. They are five nested screens now, and nesting needs this callback: without it
 * androidx routes a subscreen tap to nothing at all and the row looks dead.
 *
 * Each subscreen is the same fragment re-inflated with a root key, so the tree lives in one
 * XML and nothing has to be kept in sync. The fragment sets the toolbar title from its own
 * root, which is what keeps it right after a rotation as well as after Back.
 */
class SettingsActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, KeyboardPrefsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        val fragment = KeyboardPrefsFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
            }
        }
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(pref.key)
            .commit()
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
}
