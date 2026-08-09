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
