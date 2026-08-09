package com.kinetica.keyboard.layout

import android.content.res.AssetManager
import org.json.JSONObject

/** Parses the JSON files under assets/layouts into immutable [KeyboardLayout]s. */
object LayoutLoader {

    fun load(assets: AssetManager, path: String): KeyboardLayout {
        val text = assets.open(path).bufferedReader().use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): KeyboardLayout {
        val root = JSONObject(json)
        val keysJson = root.getJSONArray("keys")
        val keys = ArrayList<Key>(keysJson.length())
        for (i in 0 until keysJson.length()) {
            val k = keysJson.getJSONObject(i)
            // "alternates" is the current name; "longPress" is accepted as a
            // legacy alias from schema 1 layouts.
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
                ),
            )
        }
        return KeyboardLayout(
            name = root.optString("name", "unnamed"),
            locale = root.optString("locale", "en_US"),
            keys = keys,
        )
    }
}
