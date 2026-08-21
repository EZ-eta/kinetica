package com.kinetica.keyboard.settings

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.DictionaryMerger
import com.kinetica.keyboard.engine.KineticaConstants
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Dictionary management per language: shows the active base dictionary
 * (bundled or imported AOSP merge) and the personal overlay, imports an
 * AOSP-format wordlist.combined via the system file picker (merged on-device,
 * no Python and no network), and exports/imports/resets the personal
 * dictionary. Any change bumps [Prefs.DICT_GENERATION] so the IME reloads.
 */
class DictionarySettingsActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private var pendingLang = "en"

    // Language rows follow the canonical registry (Prefs.ALL_LANGUAGES) with
    // display names from the language_entries/values arrays, so a language
    // registered per ADDING_A_LANGUAGE.md §4 appears here automatically.
    // Lazy: resources are not attached at field-init time.
    private val langs: List<Pair<String, String>> by lazy {
        val values = resources.getStringArray(R.array.language_values)
        val entries = resources.getStringArray(R.array.language_entries)
        Prefs.ALL_LANGUAGES.map { lang ->
            val i = values.indexOf(lang)
            lang to (if (i >= 0) entries[i] else lang)
        }
    }

    private val pickWordlist =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBase(pendingLang, uri)
        }
    private val createPersonalExport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportPersonal(pendingLang, uri)
        }
    private val pickPersonalImport =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPersonal(pendingLang, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }
        setContentView(ScrollView(this).apply { addView(container) })
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun activeLanguage(): String =
        prefs().getString(Prefs.LANGUAGE, Prefs.DEFAULT_LANGUAGE) ?: Prefs.DEFAULT_LANGUAGE

    /** Signals the IME that stored dictionary data changed. */
    private fun bumpGeneration() {
        val p = prefs()
        p.edit().putInt(Prefs.DICT_GENERATION, p.getInt(Prefs.DICT_GENERATION, 0) + 1).apply()
    }

    private data class LangState(
        val lang: String,
        val label: String,
        val baseSource: String,
        val baseWords: Int,
        val updatedAt: Long?,
        val hasOverride: Boolean,
        val personalWords: Int,
        val blockedWords: Int,
    )

    private fun refresh() {
        io.execute {
            val states = langs.map { (lang, label) ->
                val info = DictionaryStore.readInfo(this, lang)
                val override = DictionaryStore.wordlistOverride(this, lang)
                val baseWords: Int
                val source: String
                val updatedAt: Long?
                if (override.exists() && info != null) {
                    baseWords = info.words
                    source = info.source
                    updatedAt = info.updatedAt
                } else {
                    baseWords = countAssetWords(lang)
                    source = getString(R.string.dict_source_bundled)
                    updatedAt = null
                }
                val personal = try {
                    KineticaDb.get(this).userWords().countForLanguage(lang)
                } catch (e: RuntimeException) {
                    0
                }
                val blocked = try {
                    KineticaDb.get(this).blockedWords().countForLanguage(lang)
                } catch (e: RuntimeException) {
                    0
                }
                LangState(
                    lang, label, source, baseWords, updatedAt, override.exists(),
                    personal, blocked,
                )
            }
            main.post { if (!isDestroyed) render(states) }
        }
    }

    private fun countAssetWords(lang: String): Int = try {
        assets.open("dictionaries/${lang}_wordlist.txt").bufferedReader().useLines { seq ->
            seq.count()
        }
    } catch (e: IOException) {
        0
    }

    private fun render(states: List<LangState>) {
        container.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        val active = activeLanguage()

        for (s in states) {
            container.addView(
                TextView(this).apply {
                    text = if (s.lang == active) {
                        getString(R.string.dict_lang_header_active, s.label)
                    } else {
                        s.label
                    }
                    textSize = 20f
                    setPadding(0, pad * 2, 0, pad / 2)
                },
            )
            val updated = s.updatedAt?.let {
                DateFormat.getDateTimeInstance().format(Date(it))
            } ?: getString(R.string.dict_updated_bundled)
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.dict_base_line, s.baseSource, s.baseWords, updated)
                },
            )
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.dict_personal_line, s.personalWords)
                    setPadding(0, 0, 0, pad / 2)
                },
            )
            container.addView(
                Button(this).apply {
                    text = getString(R.string.dict_import_base)
                    setOnClickListener {
                        pendingLang = s.lang
                        pickWordlist.launch(arrayOf("*/*"))
                    }
                },
            )
            if (s.hasOverride) {
                container.addView(
                    Button(this).apply {
                        text = getString(R.string.dict_remove_base)
                        setOnClickListener {
                            AlertDialog.Builder(this@DictionarySettingsActivity)
                                .setMessage(getString(R.string.dict_remove_base_confirm, s.label))
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    DictionaryStore.removeOverride(this@DictionarySettingsActivity, s.lang)
                                    bumpGeneration()
                                    refresh()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    },
                )
            }
            if (s.personalWords > 0) {
                container.addView(
                    Button(this).apply {
                        text = getString(R.string.dict_manage_personal, s.personalWords)
                        setOnClickListener { showPersonalWords(s.lang, s.label) }
                    },
                )
            }
            container.addView(
                Button(this).apply {
                    text = getString(R.string.dict_manage_blocked, s.blockedWords)
                    setOnClickListener { showBlockedWords(s.lang, s.label) }
                },
            )
            container.addView(
                Button(this).apply {
                    text = getString(R.string.dict_export_personal)
                    setOnClickListener {
                        pendingLang = s.lang
                        createPersonalExport.launch("kinetica_personal_${s.lang}.json")
                    }
                },
            )
            container.addView(
                Button(this).apply {
                    text = getString(R.string.dict_import_personal)
                    setOnClickListener {
                        pendingLang = s.lang
                        pickPersonalImport.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                },
            )
            container.addView(
                Button(this).apply {
                    text = getString(R.string.dict_reset_personal)
                    setOnClickListener {
                        AlertDialog.Builder(this@DictionarySettingsActivity)
                            .setMessage(getString(R.string.dict_reset_confirm, s.label))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                io.execute {
                                    try {
                                        KineticaDb.get(this@DictionarySettingsActivity)
                                            .userWords().clearLanguage(s.lang)
                                    } catch (e: RuntimeException) {
                                        toastLater(R.string.dict_db_error)
                                    }
                                    main.post {
                                        if (!isDestroyed) {
                                            bumpGeneration()
                                            refresh()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
            )
        }
        container.addView(
            TextView(this).apply {
                text = getString(R.string.dict_import_note)
                setPadding(0, pad * 2, 0, 0)
                textSize = 13f
            },
        )
    }

    // ------------------------------------------------- per-word personal edit

    /**
     * Per-word delete for the personal dictionary. Until now the only way to
     * remove a learned word was resetting the whole language, and every
     * historical need was for exactly one word: a misfire that got committed,
     * learned, and then won the same gesture again ("cuñado" at 6, "quinndi",
     * "qd"). `UserWordDao.delete` already existed and
     * had no caller.
     *
     * A dialog rather than a screen of its own: the list is read-only apart from
     * the delete and nothing here needs to survive a rotation.
     *
     * The search field is what makes the delete reachable. The
     * list is count-ordered on purpose - the word you are looking for is the one
     * distorting your ranking - and that is exactly what makes scrolling to a
     * NAMED word painful once the dictionary is real: a measured personal
     * dictionary held 4,266 learned Italian words.
     * Filtering is client-side because `allForLanguage` already returned every
     * row before this dialog was built, so a `WHERE word LIKE` would be a round
     * trip per keystroke for data already in memory.
     *
     * `setItems` and an adapter are mutually exclusive, which is why this uses
     * the latter: the visible list has to be rebuilt as the query changes.
     * **The click handler resolves through `shown`, never through `rows`** -
     * an index taken against the unfiltered list would delete whatever happens
     * to sit at that visual position, which on this screen means deleting the
     * wrong learned word (`PersonalWordRowsTest`
     * .tappingAFilteredRowResolvesToTheWordUnderTheFinger pins it).
     */
    private fun showPersonalWords(lang: String, label: String) {
        io.execute {
            val rows = try {
                PersonalWordRows.sortedForDisplay(
                    KineticaDb.get(this).userWords().allForLanguage(lang)
                        .map { it.word to it.frequency },
                )
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_db_error)
                return@execute
            }
            main.post {
                if (isDestroyed) return@post
                if (rows.isEmpty()) {
                    refresh()
                    return@post
                }
                showPersonalWordsDialog(lang, label, rows)
            }
        }
    }

    /**
     * The block list for one language: add a spelling, tap a row to lift it.
     *
     * Deliberately a plain list with no search box, unlike the learned words -
     * that list runs to thousands of rows, this one holds the handful of things
     * a user has actually objected to.
     *
     * Every change bumps DICT_GENERATION, which is what makes the running
     * keyboard rebuild its trie; without it a blocked word stays decodable until
     * the next dictionary load.
     */
    private fun showBlockedWords(lang: String, label: String) {
        io.execute {
            val rows = try {
                KineticaDb.get(this).blockedWords().allForLanguage(lang).map { it.word }
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_db_error)
                return@execute
            }
            main.post { if (!isDestroyed) showBlockedWordsDialog(lang, label, rows) }
        }
    }

    private fun showBlockedWordsDialog(lang: String, label: String, rows: List<String>) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val entry = EditText(this).apply {
            setHint(R.string.dict_blocked_add_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val empty = TextView(this).apply {
            setText(R.string.dict_blocked_empty)
            setPadding(pad)
            visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        val list = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(rows))
        list.adapter = adapter
        list.visibility = if (rows.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(entry)
            addView(empty)
            addView(list)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_blocked_title, label))
            .setPositiveButton(R.string.dict_blocked_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, which, _ ->
            val word = adapter.getItem(which) ?: return@setOnItemClickListener
            dialog.dismiss()
            setBlocked(lang, word, blocked = false)
        }
        dialog.setView(view)
        dialog.show()
        // Overridden after show() so adding a word does not dismiss the dialog -
        // blocking several in a row is the normal case.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val word = entry.text.toString().trim().lowercase()
            if (word.isEmpty()) return@setOnClickListener
            entry.setText("")
            adapter.remove(word)
            adapter.add(word)
            adapter.sort { a, b -> a.compareTo(b) }
            adapter.notifyDataSetChanged()
            empty.visibility = android.view.View.GONE
            list.visibility = android.view.View.VISIBLE
            setBlocked(lang, word, blocked = true)
        }
    }

    private fun setBlocked(lang: String, word: String, blocked: Boolean) {
        val now = System.currentTimeMillis()
        io.execute {
            try {
                val dao = KineticaDb.get(this).blockedWords()
                if (blocked) dao.block(word, lang, now) else dao.unblock(word, lang)
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_db_error)
                return@execute
            }
            main.post {
                if (isDestroyed) return@post
                bumpGeneration()
                refresh()
            }
        }
    }

    private fun labelFor(row: Pair<String, Int>): String = getString(
        if (PersonalWordRows.isInDecode(row.second)) {
            R.string.dict_word_row_active
        } else {
            R.string.dict_word_row_inactive
        },
        row.first, row.second,
    )

    private fun showPersonalWordsDialog(
        lang: String,
        label: String,
        rows: List<Pair<String, Int>>,
    ) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val search = EditText(this).apply {
            setHint(R.string.dict_word_search_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val empty = TextView(this).apply {
            setText(R.string.dict_word_search_empty)
            setPadding(pad)
            visibility = android.view.View.GONE
        }
        val list = ListView(this)
        // `shown` is the single source of truth for what the finger can hit, and
        // the adapter and the click handler both read it. Keeping one list
        // rather than re-deriving the filter on click is what makes the
        // index-mismatch bug unrepresentable rather than merely tested for.
        val shown = ArrayList(rows)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            ArrayList(shown.map { labelFor(it) }),
        )
        list.adapter = adapter

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search)
            addView(empty)
            addView(list)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_manage_personal_title, label))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        list.setOnItemClickListener { _, _, which, _ ->
            val word = shown[which].first
            dialog.dismiss()
            confirmDeleteWord(lang, word)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                shown.clear()
                shown.addAll(PersonalWordRows.filtered(rows, s?.toString() ?: ""))
                adapter.clear()
                adapter.addAll(shown.map { labelFor(it) })
                adapter.notifyDataSetChanged()
                val none = shown.isEmpty()
                empty.visibility = if (none) android.view.View.VISIBLE else android.view.View.GONE
                list.visibility = if (none) android.view.View.GONE else android.view.View.VISIBLE
            }
        })
        dialog.show()
    }

    private fun confirmDeleteWord(lang: String, word: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dict_word_delete_confirm, word))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                io.execute {
                    try {
                        KineticaDb.get(this).userWords().delete(word, lang)
                    } catch (e: RuntimeException) {
                        toastLater(R.string.dict_db_error)
                        return@execute
                    }
                    main.post {
                        if (!isDestroyed) {
                            // Without the bump the row is gone from Room but the
                            // word survives in the resident trie AND in the live
                            // personalCounts map until the next dictionary load,
                            // so the boost it was deleted for would keep applying.
                            bumpGeneration()
                            Toast.makeText(
                                this, getString(R.string.dict_word_deleted, word), Toast.LENGTH_SHORT,
                            ).show()
                            refresh()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------- base import

    private fun importBase(lang: String, uri: Uri) {
        io.execute {
            try {
                // Merge against the BUNDLED primary, never against a previous
                // import, so re-importing can only replace, not compound.
                val primary = assets.open("dictionaries/${lang}_wordlist.txt")
                    .bufferedReader().use { DictionaryMerger.readPrimary(it) }
                val result = contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    DictionaryMerger.merge(primary, it, lang)
                } ?: throw IOException("cannot open $uri")
                if (result.aospParsed == 0) {
                    toastLater(R.string.dict_import_not_wordlist)
                    return@execute
                }
                val sb = StringBuilder(result.rows.size * 12)
                for ((w, c) in result.rows) sb.append(w).append('\t').append(c).append('\n')
                DictionaryStore.wordlistOverride(this, lang).writeText(sb.toString())
                DictionaryStore.writeInfo(
                    this, lang,
                    DictionaryStore.Info(
                        source = getString(R.string.dict_source_aosp),
                        words = result.rows.size,
                        added = result.added,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                main.post {
                    if (!isDestroyed) {
                        bumpGeneration()
                        Toast.makeText(
                            this,
                            getString(R.string.dict_import_done, result.added),
                            Toast.LENGTH_LONG,
                        ).show()
                        refresh()
                    }
                }
            } catch (e: IOException) {
                toastLater(R.string.dict_import_failed)
            }
        }
    }

    // ---------------------------------------------------- personal im/export

    private fun exportPersonal(lang: String, uri: Uri) {
        io.execute {
            try {
                val rows = KineticaDb.get(this).userWords().allForLanguage(lang)
                val arr = JSONArray()
                for (r in rows) {
                    arr.put(JSONObject().put("word", r.word).put("count", r.frequency))
                }
                val doc = JSONObject()
                    .put("format", "kinetica-personal-1")
                    .put("lang", lang)
                    .put("words", arr)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(doc.toString(2))
                } ?: throw IOException("cannot open $uri")
                toastLater(R.string.dict_export_done, rows.size)
            } catch (e: IOException) {
                toastLater(R.string.dict_export_failed)
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_export_failed)
            }
        }
    }

    private fun importPersonal(lang: String, uri: Uri) {
        // The original spec asked the user merge-vs-replace at import time:
        // merging is the safe default for topping up from a backup, but
        // restoring a curated export onto a polluted dictionary needs a clean
        // slate (accumulated pollution, stale misfire words).
        AlertDialog.Builder(this)
            .setTitle(R.string.dict_personal_import_mode_title)
            .setMessage(R.string.dict_personal_import_mode_message)
            .setPositiveButton(R.string.dict_personal_import_merge) { _, _ ->
                runPersonalImport(lang, uri, replace = false)
            }
            .setNegativeButton(R.string.dict_personal_import_replace) { _, _ ->
                runPersonalImport(lang, uri, replace = true)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun runPersonalImport(lang: String, uri: Uri, replace: Boolean) {
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readText()
                } ?: throw IOException("cannot open $uri")
                val doc = JSONObject(text)
                val words = doc.getJSONArray("words")
                val dao = KineticaDb.get(this).userWords()
                val now = System.currentTimeMillis()
                // Clear only after the file parsed as a personal export, so a
                // wrong file picked in replace mode cannot wipe the language.
                if (replace) dao.clearLanguage(lang)
                var imported = 0
                for (i in 0 until words.length()) {
                    val o = words.getJSONObject(i)
                    val word = o.getString("word").lowercase()
                    val count = o.getInt("count")
                    if (word.isEmpty() || word.length > 24 || count < 1) continue
                    if (!WORD_RE.matches(word)) continue
                    // An import is a deliberate act: clamp up to the merge
                    // floor so every imported word decodes immediately instead
                    // of waiting out the anti-accident gate
                    // (KineticaConstants.PERSONAL_MERGE_MIN_COUNT).
                    dao.upsertAdd(
                        word, lang,
                        count.coerceIn(
                            KineticaConstants.PERSONAL_MERGE_MIN_COUNT,
                            MAX_IMPORT_COUNT,
                        ),
                        now,
                    )
                    imported++
                }
                main.post {
                    if (!isDestroyed) {
                        bumpGeneration()
                        Toast.makeText(
                            this,
                            getString(R.string.dict_personal_import_done, imported),
                            Toast.LENGTH_LONG,
                        ).show()
                        refresh()
                    }
                }
            } catch (e: IOException) {
                toastLater(R.string.dict_import_failed)
            } catch (e: JSONException) {
                toastLater(R.string.dict_import_not_personal)
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_import_failed)
            }
        }
    }

    private fun toastLater(resId: Int, vararg args: Any) {
        main.post {
            if (!isDestroyed) {
                Toast.makeText(this, getString(resId, *args), Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        // Same shape the IME accepts when learning; keeps imports sane.
        val WORD_RE = Regex("^\\p{L}+(?:'\\p{L}+)*$")
        const val MAX_IMPORT_COUNT = 10_000
    }
}
