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
