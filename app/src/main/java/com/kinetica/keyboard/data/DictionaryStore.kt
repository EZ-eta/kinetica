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

package com.kinetica.keyboard.data

import android.content.Context
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * App-internal storage for imported base dictionaries. An imported (merged)
 * wordlist lives beside a small info JSON describing where it came from; when
 * the wordlist file exists it overrides the bundled asset at load time, and
 * deleting it reverts to the bundled dictionary.
 */
object DictionaryStore {

    data class Info(
        val source: String,
        val words: Int,
        val added: Int,
        val updatedAt: Long,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "dictionaries").apply { mkdirs() }

    fun wordlistOverride(context: Context, lang: String): File =
        File(dir(context), "${lang}_wordlist.txt")

    private fun infoFile(context: Context, lang: String): File =
        File(dir(context), "${lang}_info.json")

    fun readInfo(context: Context, lang: String): Info? {
        val f = infoFile(context, lang)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            Info(
                source = o.getString("source"),
                words = o.getInt("words"),
                added = o.optInt("added", 0),
                updatedAt = o.getLong("updatedAt"),
            )
        } catch (e: JSONException) {
            null
        }
    }

    fun writeInfo(context: Context, lang: String, info: Info) {
        infoFile(context, lang).writeText(
            JSONObject()
                .put("source", info.source)
                .put("words", info.words)
                .put("added", info.added)
                .put("updatedAt", info.updatedAt)
                .toString(),
        )
    }

    fun removeOverride(context: Context, lang: String) {
        wordlistOverride(context, lang).delete()
        infoFile(context, lang).delete()
    }
}
