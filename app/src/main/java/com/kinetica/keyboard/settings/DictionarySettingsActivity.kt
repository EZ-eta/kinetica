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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Dictionary management per language: shows the active base dictionary (bundled or imported AOSP
 * merge) and the personal overlay, imports an AOSP-format wordlist.combined via the system file
 * picker (merged on-device, no network), and exports, imports and resets the personal dictionary.
 * Any change bumps [Prefs.DICT_GENERATION] so the IME reloads.
 */
class DictionarySettingsActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private var pendingLang = "en"

    // Language rows follow the canonical registry (Prefs.ALL_LANGUAGES) with display names from
    // the language_entries/values arrays, so a language registered per ADDING_A_LANGUAGE.md §4
    // appears here automatically. Lazy: resources are not attached at field-init time.
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

    /** The run end this screen last drew: a restore on the Backup screen changes these counts. */
    private var seenEnd = BackupRun.progress.ended

    override fun onStart() {
        super.onStart()
        if (seenEnd != BackupRun.progress.ended) {
            seenEnd = BackupRun.progress.ended
            refresh()
        }
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

    /** The same, for the expansion table, which reloads on its own counter. */
    private fun bumpExpansionGeneration() {
        val p = prefs()
        p.edit()
            .putInt(Prefs.EXPANSION_GENERATION, p.getInt(Prefs.EXPANSION_GENERATION, 0) + 1)
            .apply()
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

    /** Languages open on this screen; all start closed. */
    private val expanded = HashSet<String>()
    private var lastStates: List<LangState> = emptyList()

    private fun render(states: List<LangState>) {
        lastStates = states
        container.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        val active = activeLanguage()

        // By name, as a reader looks for one; the registry order is the cycle order.
        for (s in DictionaryRows.byName(states, { it.label }, Locale.getDefault())) {
            val open = s.lang in expanded
            container.addView(
                TextView(this).apply {
                    val label = if (s.lang == active) getString(R.string.dict_lang_header_active, s.label) else s.label
                    text = getString(if (open) R.string.dict_lang_open else R.string.dict_lang_closed, label)
                    textSize = 20f
                    setPadding(0, pad * 2, 0, pad / 2)
                    setOnClickListener {
                        if (!expanded.remove(s.lang)) expanded.add(s.lang)
                        render(lastStates)
                    }
                },
            )
            if (!open) continue
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
            // Learned phrases are a separate store and a separate consent, so they get their own
            // reset: turning phrase learning off can discard what it recorded and keep the words.
            container.addView(
                Button(this).apply {
                    text = getString(R.string.dict_clear_phrases)
                    setOnClickListener {
                        AlertDialog.Builder(this@DictionarySettingsActivity)
                            .setMessage(getString(R.string.dict_clear_phrases_confirm, s.label))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                io.execute {
                                    try {
                                        KineticaDb.get(this@DictionarySettingsActivity)
                                            .userBigrams().clearLanguage(s.lang)
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
     * Per-word edit for the personal dictionary: a misfire that got committed, learned and then
     * won the same gesture again ("cuñado" at 6, "quinndi", "qd") is removed without resetting the
     * language.
     *
     * A dialog, not a screen of its own: nothing here needs to survive a rotation.
     *
     * The list is count-ordered (see [PersonalWordRows.sortedForDisplay]), so a named word needs
     * the search field: one personal dictionary held 4,266 learned Italian words. Filtering is
     * client-side because `allForLanguage` already returned every row.
     *
     * An adapter, not `setItems`, because the visible list is rebuilt as the query changes. The
     * click handler resolves through `shown`, never `rows`: an index into the unfiltered list
     * would delete the wrong word (`PersonalWordRowsTest`
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
     * A plain list with no search box: unlike the learned words, which run to thousands, it holds
     * the handful of words a user has objected to.
     *
     * Every change bumps DICT_GENERATION so the running keyboard rebuilds its trie; otherwise a
     * blocked word stays decodable until the next dictionary load.
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
        // Overridden after show so adding a word does not dismiss the dialog: blocking several
        // in a row is the normal case.
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

    /**
     * Blocks or unblocks [word] in every enabled language, not only [lang].
     *
     * The table stays keyed on (word, lang), so a later language is unaffected, but one action
     * writes every enabled language: a word held by two dictionaries would otherwise stay on offer
     * from the other one (`kyra` is English rank 27341 and Italian 33065).
     *
     * Only the languages enabled now, so a word blocked while English is the only one enabled
     * does not silently disappear from a language added later.
     */
    private fun setBlocked(lang: String, word: String, blocked: Boolean) {
        val now = System.currentTimeMillis()
        val langs = (KeyboardConfig.from(prefs()).enabledLanguages + lang).distinct()
        io.execute {
            try {
                val dao = KineticaDb.get(this).blockedWords()
                for (l in langs) {
                    if (blocked) dao.block(word, l, now) else dao.unblock(word, l)
                }
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

    private fun labelFor(row: Pair<String, Int>): String = when (PersonalWordRows.rowState(row.second)) {
        PersonalWordRows.RowState.SUGGESTED ->
            resources.getQuantityString(R.plurals.dict_word_row_suggested, row.second, row.first, row.second)
        PersonalWordRows.RowState.BELOW_FLOOR -> resources.getQuantityString(
            R.plurals.dict_word_row_below_floor, row.second, row.first, row.second,
            KineticaConstants.PERSONAL_MERGE_MIN_COUNT,
        )
        PersonalWordRows.RowState.TAKEN_BACK -> getString(R.string.dict_word_row_taken_back, row.first)
    }

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
        val help = TextView(this).apply {
            text = getString(R.string.dict_words_help, KineticaConstants.PERSONAL_MERGE_MIN_COUNT)
            setPadding(pad, pad / 2, pad, 0)
        }
        val list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        // Half the screen, fixed: a list sized to its content re-centres the dialog after every
        // delete and keystroke, moving the buttons each time.
        list.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.heightPixels / 2,
        )
        // `shown` is the single source of truth for what the finger can hit; the adapter and the
        // click handler both read it, so the index cannot mismatch.
        val shown = ArrayList(rows)
        // The selection is words, not positions: a position is in the filtered list, so after the
        // query changes it holds a different word (see PersonalWordRows.checkedPositions).
        val checked = LinkedHashSet<String>()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            ArrayList(shown.map { labelFor(it) }),
        )
        list.adapter = adapter

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(help)
            addView(search)
            addView(empty)
            addView(list)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_manage_personal_title, label))
            .setView(view)
            .setPositiveButton(R.string.dict_word_delete_checked, null)
            .setNeutralButton(R.string.dict_word_raise_checked, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun repaintChecks() {
            val positions = PersonalWordRows.checkedPositions(shown, checked)
            for (i in shown.indices) list.setItemChecked(i, i in positions)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = checked.isNotEmpty()
        }

        list.setOnItemClickListener { _, _, which, _ ->
            val word = shown[which].first
            if (!checked.remove(word)) checked.add(word)
            repaintChecks()
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
                // Ticks on rows the query now hides are kept, so a user can filter, tick,
                // filter again and delete the lot in one action.
                repaintChecks()
            }
        })
        dialog.show()
        repaintChecks()
        // Overridden after show so a delete does not dismiss the dialog: clearing out several
        // words in a row is the normal case.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val words = PersonalWordRows.wordsToDelete(rows, checked)
            if (words.isEmpty()) return@setOnClickListener
            confirmDeleteWords(lang, words) {
                checked.clear()
                dialog.dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val words = PersonalWordRows.wordsToDelete(rows, checked)
            if (words.isEmpty()) return@setOnClickListener
            raiseWords(lang, words)
            checked.clear()
            dialog.dismiss()
        }
    }

    /**
     * One commit more for each of [words], as sliding it up on the bar would give. Not
     * confirmed: it is one step and the same list takes it back.
     */
    private fun raiseWords(lang: String, words: List<String>) {
        io.execute {
            try {
                val dao = KineticaDb.get(this).userWords()
                val now = System.currentTimeMillis()
                for (w in words) dao.upsertAdd(w, lang, 1, now)
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_db_error)
                return@execute
            }
            main.post {
                if (!isDestroyed) {
                    bumpGeneration()
                    Toast.makeText(
                        this,
                        resources.getQuantityString(R.plurals.dict_words_raised, words.size, words.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                    refresh()
                }
            }
        }
    }

    /**
     * One confirmation for a whole batch, then one pass on [io].
     *
     * [bumpGeneration] makes the running keyboard rebuild its trie: otherwise the rows leave Room
     * but the words survive in the resident trie and the live personalCounts map until the next
     * load, and the weight they were deleted for keeps applying. Bumped once per batch.
     */
    private fun confirmDeleteWords(lang: String, words: List<String>, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dict_words_delete_confirm, words.size, words.size,
                ),
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                io.execute {
                    try {
                        val dao = KineticaDb.get(this).userWords()
                        for (w in words) dao.delete(w, lang)
                    } catch (e: RuntimeException) {
                        toastLater(R.string.dict_db_error)
                        return@execute
                    }
                    main.post {
                        if (!isDestroyed) {
                            bumpGeneration()
                            Toast.makeText(
                                this,
                                resources.getQuantityString(
                                    R.plurals.dict_words_deleted, words.size, words.size,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDone()
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
                // Merge against the bundled primary, never a previous import, so re-importing
                // replaces and never compounds.
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
        // Merge or replace, asked at import time: merging is the safe default for topping up from
        // a backup, but restoring a curated export onto a polluted dictionary needs a clean slate.
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
                // Clear only after the file parsed as a personal export, so a wrong file picked in
                // replace mode cannot wipe the language.
                if (replace) dao.clearLanguage(lang)
                var imported = 0
                for (i in 0 until words.length()) {
                    val o = words.getJSONObject(i)
                    val word = o.getString("word").lowercase()
                    val count = o.getInt("count")
                    if (word.isEmpty() || word.length > 24 || count < 1) continue
                    if (!WORD_RE.matches(word)) continue
                    // An import is deliberate: clamp up to the merge floor so every imported word
                    // decodes at once instead of waiting out the anti-accident gate
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
        const val MAX_IMPORT_COUNT = BackupRun.MAX_IMPORT_COUNT
    }
}
