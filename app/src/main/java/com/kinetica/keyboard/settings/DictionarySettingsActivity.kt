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
    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) exportBackup(uri, includePhrases)
        }
    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBackup(uri)
        }

    /** Ticked in the export dialog; phrases ride only when it is. */
    private var includePhrases = false

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
            // Learned phrases are a separate store and a separate consent, so they get a
            // separate reset: someone who turns phrase learning off should be able to throw
            // away what it already recorded without losing their learned words.
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

        // Whole-keyboard backup, below the per-language rows because it is not per language:
        // it carries every setting, every learned word in every language, the blocked words,
        // the chords and the edge swipes. Asked for as the single top priority by someone
        // moving between phones.
        container.addView(
            TextView(this).apply {
                text = getString(R.string.backup_heading)
                setPadding(0, pad * 2, 0, 0)
                textSize = 16f
            },
        )
        container.addView(
            TextView(this).apply {
                text = getString(R.string.backup_note)
                setPadding(0, pad / 2, 0, 0)
                textSize = 13f
            },
        )
        container.addView(
            Button(this).apply {
                text = getString(R.string.backup_export)
                setOnClickListener { askPhrasesThenExport() }
            },
        )
        container.addView(
            Button(this).apply {
                text = getString(R.string.backup_import)
                setOnClickListener {
                    pickBackup.launch(arrayOf("text/plain", "text/*", "*/*"))
                }
            },
        )
    }

    // ------------------------------------------------------- whole-keyboard backup

    /**
     * Phrases are the one thing that needs asking about.
     *
     * Learned word pairs are opt-in in the first place because a pair is a fragment of a
     * sentence, and a backup file can be copied anywhere. Leaving them out silently would be
     * a backup that loses data; putting them in silently would undo the consent. So the
     * export asks, unticked, every time.
     */
    private fun askPhrasesThenExport() {
        io.execute {
            val pairs = try {
                Prefs.ALL_LANGUAGES.sumOf { KineticaDb.get(this).userBigrams().countForLanguage(it) }
            } catch (e: RuntimeException) {
                0
            }
            main.post {
                if (isDestroyed) return@post
                if (pairs == 0) {
                    includePhrases = false
                    createBackup.launch(BACKUP_FILENAME)
                    return@post
                }
                val checked = booleanArrayOf(false)
                AlertDialog.Builder(this)
                    .setTitle(R.string.backup_export)
                    .setMultiChoiceItems(
                        arrayOf(getString(R.string.backup_include_phrases, pairs)),
                        checked,
                    ) { _, _, isChecked -> checked[0] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        includePhrases = checked[0]
                        createBackup.launch(BACKUP_FILENAME)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun exportBackup(uri: Uri, withPhrases: Boolean) {
        io.execute {
            try {
                val data = collectBackup(withPhrases)
                val dropped = Backup.unencodable(data)
                var lines = 0
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    for (line in Backup.encode(data)) {
                        w.write(line)
                        w.write(NEWLINE)
                        lines++
                    }
                } ?: throw IOException("cannot open $uri")
                if (dropped > 0) {
                    toastLater(R.string.backup_export_partial, lines - 1, dropped)
                } else {
                    toastLater(R.string.backup_export_done, lines - 1)
                }
            } catch (e: IOException) {
                toastLater(R.string.backup_export_failed)
            } catch (e: RuntimeException) {
                toastLater(R.string.backup_export_failed)
            }
        }
    }

    /** Everything worth carrying, read on the io thread. */
    private fun collectBackup(withPhrases: Boolean): Backup.Data {
        val p = prefs()
        val prefRows = ArrayList<Backup.Pref>()
        for ((key, value) in p.all) {
            // A change counter, not a setting: copying it would leave the new device's
            // generation ahead of or behind its own data.
            if (key == Prefs.DICT_GENERATION) continue
            val row = when (value) {
                is Boolean -> Backup.Pref(key, Backup.PrefType.BOOL, value.toString())
                is Int -> Backup.Pref(key, Backup.PrefType.INT, value.toString())
                is String -> Backup.Pref(key, Backup.PrefType.STRING, value)
                is Set<*> -> Backup.Pref(
                    key, Backup.PrefType.SET,
                    value.filterIsInstance<String>().sorted().joinToString(","),
                )
                else -> null
            }
            if (row != null) prefRows.add(row)
        }
        val db = KineticaDb.get(this)
        val words = ArrayList<Backup.Word>()
        val blocked = ArrayList<Backup.Blocked>()
        val phrases = ArrayList<Backup.Phrase>()
        val base = ArrayList<String>()
        for (lang in Prefs.ALL_LANGUAGES) {
            for (r in db.userWords().allForLanguage(lang)) {
                words.add(Backup.Word(lang, r.word, r.frequency))
            }
            for (r in db.blockedWords().allForLanguage(lang)) {
                blocked.add(Backup.Blocked(lang, r.word))
            }
            if (withPhrases) {
                for (r in db.userBigrams().topN(lang, Int.MAX_VALUE)) {
                    phrases.add(Backup.Phrase(lang, r.prev, r.next, r.count))
                }
            }
            if (DictionaryStore.readInfo(this, lang) != null) base.add(lang)
        }
        val chords = db.chordShortcuts().all().map { Backup.Chord(it.chord, it.expansion) }
        return Backup.Data(prefRows, words, blocked, chords, phrases, base)
    }

    private fun importBackup(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_import)
            .setMessage(R.string.backup_import_message)
            .setPositiveButton(R.string.dict_personal_import_merge) { _, _ ->
                runBackupImport(uri, replace = false)
            }
            .setNegativeButton(R.string.dict_personal_import_replace) { _, _ ->
                runBackupImport(uri, replace = true)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun runBackupImport(uri: Uri, replace: Boolean) {
        io.execute {
            val result = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    Backup.decode(it.lineSequence())
                } ?: throw IOException("cannot open $uri")
            } catch (e: IOException) {
                toastLater(R.string.dict_import_failed)
                null
            } catch (e: RuntimeException) {
                toastLater(R.string.dict_import_failed)
                null
            }
            when (result) {
                null -> Unit
                is Backup.Result.NotABackup -> toastLater(R.string.backup_import_not_backup)
                is Backup.Result.TooNew -> toastLater(R.string.backup_import_too_new)
                is Backup.Result.Ok -> applyBackup(result, replace)
            }
        }
    }

    /**
     * Writes a decoded backup back into prefs and Room.
     *
     * Two orderings are load-bearing. The preferences go on the MAIN thread, because the
     * IME's change listener runs on whoever writes and it repaints views; and
     * [bumpGeneration] goes LAST, because it is the only thing that makes the running
     * keyboard re-read the database, so it has to see finished tables.
     */
    private fun applyBackup(ok: Backup.Result.Ok, replace: Boolean) {
        val db = KineticaDb.get(this)
        val now = System.currentTimeMillis()
        if (replace) {
            for (lang in Prefs.ALL_LANGUAGES) {
                db.userWords().clearLanguage(lang)
                db.userBigrams().clearLanguage(lang)
            }
        }
        for (w in ok.data.words) {
            db.userWords().upsertAdd(
                w.word, w.lang,
                w.count.coerceIn(KineticaConstants.PERSONAL_MERGE_MIN_COUNT, MAX_IMPORT_COUNT),
                now,
            )
        }
        for (b in ok.data.blocked) db.blockedWords().block(b.word, b.lang, now)
        for (c in ok.data.chords) db.chordShortcuts().assign(c.chord, c.expansion)
        for (p in ok.data.phrases) {
            db.userBigrams().upsertAdd(p.prev, p.next, p.lang, p.count.coerceAtMost(MAX_IMPORT_COUNT), now)
        }
        val missing = ok.data.importedBase.filter { DictionaryStore.readInfo(this, it) == null }
        main.post {
            if (isDestroyed) return@post
            val e = prefs().edit()
            for (pref in ok.data.prefs) {
                when (pref.type) {
                    Backup.PrefType.BOOL -> e.putBoolean(pref.key, pref.value == "true")
                    Backup.PrefType.INT -> pref.value.toIntOrNull()?.let { e.putInt(pref.key, it) }
                    Backup.PrefType.STRING -> e.putString(pref.key, pref.value)
                    Backup.PrefType.SET -> e.putStringSet(
                        pref.key,
                        pref.value.split(",").filter { it.isNotEmpty() }.toSet(),
                    )
                }
            }
            e.apply()
            bumpGeneration()
            refresh()
            val msg = if (missing.isEmpty()) {
                getString(R.string.backup_import_done, ok.data.words.size, ok.data.prefs.size)
            } else {
                getString(R.string.backup_import_done_missing_base, missing.joinToString(", "))
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
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

    /**
     * Blocks or unblocks [word] in every enabled language, not only [lang].
     *
     * The table stays keyed on (word, lang) and that is deliberate - blocking a junk
     * name out of the English corpus must not also remove a real Italian word spelled
     * the same. What was wrong is which rows one action wrote: a word held by two
     * dictionaries stayed available from the other one, so blocking `kyra` (English
     * rank 27341, Italian 33065) left it being offered from Italian and it kept
     * appearing.
     *
     * Only the languages enabled right now, so a word blocked while English is the only
     * one enabled does not silently disappear from a language added later.
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
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        // Half the screen, fixed. The list used to size to its content, so the dialog
        // changed height and re-centred after every delete and every keystroke in the
        // search box, which put the buttons somewhere new each time.
        list.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.heightPixels / 2,
        )
        // `shown` is the single source of truth for what the finger can hit, and
        // the adapter and the click handler both read it. Keeping one list
        // rather than re-deriving the filter on click is what makes the
        // index-mismatch bug unrepresentable rather than merely tested for.
        val shown = ArrayList(rows)
        // The selection is words, not positions. A tick is a position in the FILTERED
        // list, so after the query changes that position holds a different word; see
        // PersonalWordRows.checkedPositions.
        val checked = LinkedHashSet<String>()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
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
            .setPositiveButton(R.string.dict_word_delete_checked, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun repaintChecks() {
            val positions = PersonalWordRows.checkedPositions(shown, checked)
            for (i in shown.indices) list.setItemChecked(i, i in positions)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.isNotEmpty()
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
        // Overridden after show() so a delete does not dismiss the dialog: clearing out
        // several words in a row is the normal case, and the dialog closing after each one
        // was the reported complaint.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val words = PersonalWordRows.wordsToDelete(rows, checked)
            if (words.isEmpty()) return@setOnClickListener
            confirmDeleteWords(lang, words) {
                checked.clear()
                dialog.dismiss()
            }
        }
    }

    /**
     * One confirmation for a whole batch, then one pass on [io].
     *
     * [bumpGeneration] is what makes the running keyboard rebuild its trie: without it the
     * rows are gone from Room but the words survive in the resident trie and in the live
     * personalCounts map until the next load, so the weight they were deleted for keeps
     * applying. Bumped once for the batch rather than once per word.
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
        const val BACKUP_FILENAME = "kinetica_backup.txt"
        const val NEWLINE = "\n"
    }
}
