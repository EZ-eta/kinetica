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

package com.kinetica.keyboard.layout

import android.content.res.AssetManager
import com.kinetica.keyboard.engine.Alphabet
import org.json.JSONObject

/** Parses the JSON files under assets/layouts into immutable [KeyboardLayout]s. */
object LayoutLoader {

    fun load(assets: AssetManager, path: String): KeyboardLayout {
        val text = assets.open(path).bufferedReader().use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): KeyboardLayout {
        val root = JSONObject(json)
        val alphabet = Alphabet.forScript(root.optString("script", "latin"))
        val keysJson = root.getJSONArray("keys")
        val keys = ArrayList<Key>(keysJson.length())
        for (i in 0 until keysJson.length()) {
            val k = keysJson.getJSONObject(i)
            // "longPress" is the schema 1 name for "alternates", still accepted.
            val altKey = if (k.has("alternates")) "alternates" else "longPress"
            val alternates = if (k.has(altKey)) {
                val arr = k.getJSONArray(altKey)
                List(arr.length()) { arr.getString(it) }
            } else {
                emptyList()
            }
            keys.add(
                Key(
                    id = k.getString("id"),
                    type = KeyType.fromJson(k.getString("type")),
                    label = k.optString("label", ""),
                    output = k.optString("output", ""),
                    x = k.getDouble("x").toFloat(),
                    y = k.getDouble("y").toFloat(),
                    w = k.getDouble("w").toFloat(),
                    h = k.getDouble("h").toFloat(),
                    hint = if (k.has("hint")) k.getString("hint") else null,
                    alternates = alternates,
                    alphabet = alphabet,
                ),
            )
        }
        return KeyboardLayout(
            name = root.optString("name", "unnamed"),
            locale = root.optString("locale", "en_US"),
            keys = keys,
            // Optional, so no schema bump: absent means the accents are foreign, as the
            // English layout and the plain-qwerty fallback want.
            nativeAccents = root.optBoolean("nativeAccents", false),
            // Optional too: absent leaves the arrangement swap enabled, as every
            // QWERTY-derived layout wants.
            fixedArrangement = root.optBoolean("fixedArrangement", false),
            alphabet = alphabet,
        )
    }
}
