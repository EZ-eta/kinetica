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

import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import java.io.IOException

/**
 * Renders THIRD_PARTY_NOTICES, which the build copies from the repo root into the APK assets so
 * the shipped screen cannot drift from the file. Plain local text, so the licenses screen keeps
 * the zero-network guarantee too.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(pad)
            setTextIsSelectable(true)
            text = try {
                assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                getString(R.string.licenses_missing)
            }
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val NOTICES_ASSET = "third_party_notices.txt"
    }
}
