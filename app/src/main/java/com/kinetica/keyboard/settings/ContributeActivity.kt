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

package com.kinetica.keyboard.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R

/**
 * The project's Ko-fi link. The browser opens it, so the keyboard itself still
 * declares no network permission; with no browser the link is shown to copy.
 */
class ContributeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val fallback = TextView(this).apply {
            text = getString(R.string.contribute_no_browser, KO_FI_URL)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(this@ContributeActivity).apply { setText(R.string.contribute_text) })
            addView(
                Button(this@ContributeActivity).apply {
                    setText(R.string.contribute_button)
                    isAllCaps = false
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KO_FI_URL)))
                        } catch (e: ActivityNotFoundException) {
                            fallback.visibility = View.VISIBLE
                        }
                    }
                },
            )
            addView(fallback)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        /** The link the README publishes; `ContributeLinkTest` keeps the two the same. */
        const val KO_FI_URL = "https://ko-fi.com/ez_eta"
    }
}
