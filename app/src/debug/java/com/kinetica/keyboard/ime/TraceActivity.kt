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

package com.kinetica.keyboard.ime

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import java.io.IOException

/**
 * The debug build's only screen: what the decode trace has recorded, and how to
 * get it off the device.
 *
 * Declared in the debug manifest alone, so nothing about it merges into a release build. Export
 * goes through the storage-access framework, like the personal-dictionary export: no file
 * provider, no new permission, and the file lands wherever the user picks.
 */
class TraceActivity : AppCompatActivity() {

    private lateinit var stats: TextView

    private val createExport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) export(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.trace_title)
        val pad = (16 * resources.displayMetrics.density).toInt()

        stats = TextView(this).apply { textSize = 15f }

        val toggle = SwitchCompat(this).apply {
            text = getString(R.string.trace_recording)
            textSize = 16f
            isChecked = TraceRecorder.recording
            setOnCheckedChangeListener { _, on -> TraceRecorder.recording = on }
        }

        // Shown on the screen itself: this build writes down what is typed on it.
        val explain = TextView(this).apply {
            text = getString(R.string.trace_explain)
            textSize = 13f
        }

        val exportButton = Button(this).apply {
            text = getString(R.string.trace_export)
            setOnClickListener { createExport.launch("kinetica_trace.log") }
        }

        val clearButton = Button(this).apply {
            text = getString(R.string.trace_clear)
            setOnClickListener {
                TraceRecorder.clear()
                refresh()
                Toast.makeText(this@TraceActivity, R.string.trace_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(toggle)
            addView(stats)
            addView(exportButton)
            addView(clearButton)
            addView(explain)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val bytes = TraceRecorder.sizeBytes()
        stats.text = if (bytes == 0L) {
            getString(R.string.trace_empty)
        } else {
            resources.getQuantityString(
                R.plurals.trace_stats,
                TraceRecorder.lines.toInt(),
                TraceRecorder.lines,
                readableSize(bytes),
            )
        }
    }

    private fun export(uri: Uri) {
        val ok = try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(TraceRecorder.readAll())
            } != null
        } catch (e: IOException) {
            false
        }
        val msg = if (ok) R.string.trace_export_done else R.string.trace_export_failed
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
