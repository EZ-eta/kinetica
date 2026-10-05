package com.kinetica.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.DictionaryStore
import com.kinetica.keyboard.data.KineticaDb
import com.kinetica.keyboard.engine.KineticaConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A backup import or restore, owned by the process and not by the screen that started it, so
 * closing the screen cannot skip the settings or the reload. The tables are one Room transaction,
 * so a kill leaves the keyboard as it was.
 */
object BackupRun {

    /** Where a backup comes from. */
    sealed class Source {
        data class Document(val uri: Uri) : Source()

        /** The pre-import copy. Never snapshots itself: that would spend the only undo on the undo. */
        object Snapshot : Source()
    }

    /** Read by the screen about ten times a second to draw the dialog. */
    val progress = BackupProgress()

    // Single-threaded and never shut down: a run must outlive any screen.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Starts a run, or returns false while one is going. */
    fun start(context: Context, source: Source, replace: Boolean): Boolean {
        if (!progress.begin()) return false
        val app = context.applicationContext
        worker.execute {
            try {
                run(app, source, replace)
            } catch (e: IOException) {
                fail(app, failedRes(source))
            } catch (e: RuntimeException) {
                fail(app, failedRes(source))
            }
        }
        return true
    }

    private fun failedRes(source: Source): Int =
        if (source is Source.Snapshot) R.string.backup_restore_failed else R.string.dict_import_failed

    private fun run(app: Context, source: Source, replace: Boolean) {
        val result = when (source) {
            is Source.Document -> app.contentResolver.openInputStream(source.uri)?.bufferedReader()?.use {
                Backup.decode(it.lineSequence())
            } ?: throw IOException("cannot open ${source.uri}")
            Source.Snapshot -> snapshotFile(app).bufferedReader().use { Backup.decode(it.lineSequence()) }
        }
        when (result) {
            is Backup.Result.NotABackup -> fail(app, R.string.backup_import_not_backup)
            is Backup.Result.TooNew -> fail(app, R.string.backup_import_too_new)
            is Backup.Result.Ok -> {
                if (source !is Source.Snapshot) {
                    progress.phase(BackupProgress.Phase.SAVING)
                    writeSnapshot(app)
                }
                apply(app, result.data, replace)
                val missing = result.data.importedBase.filter { DictionaryStore.readInfo(app, it) == null }
                main.post {
                    writePrefs(app, result.data)
                    val msg = when {
                        source is Source.Snapshot -> app.getString(R.string.backup_restore_done)
                        missing.isEmpty() ->
                            app.getString(R.string.backup_import_done, result.data.words.size, result.data.prefs.size)
                        else -> app.getString(R.string.backup_import_done_missing_base, missing.joinToString(", "))
                    }
                    progress.end()
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fail(app: Context, resId: Int) {
        main.post {
            progress.end()
            Toast.makeText(app, app.getString(resId), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Every table in one transaction, so a kill half way rolls the whole import back and a
     * replace never leaves the dictionaries emptied.
     */
    private fun apply(app: Context, data: Backup.Data, replace: Boolean) {
        val db = KineticaDb.get(app)
        val now = System.currentTimeMillis()
        progress.writing(BackupProgress.recordCount(data))
        db.runInTransaction {
            if (replace) {
                for (lang in Prefs.ALL_LANGUAGES) {
                    db.userWords().clearLanguage(lang)
                    db.userBigrams().clearLanguage(lang)
                }
            }
            for (w in data.words) {
                db.userWords().upsertAdd(
                    w.word, w.lang,
                    w.count.coerceIn(KineticaConstants.PERSONAL_MERGE_MIN_COUNT, MAX_IMPORT_COUNT),
                    now,
                )
                progress.advance()
            }
            for (b in data.blocked) {
                db.blockedWords().block(b.word, b.lang, now)
                progress.advance()
            }
            for (c in data.chords) {
                db.chordShortcuts().assign(c.chord, c.expansion)
                progress.advance()
            }
            // Grouped so one trigger's targets are written as one list, the way the DAO owns
            // them: a per-row assign would delete the trigger's earlier positions each time.
            for ((trigger, rows) in data.expansions.groupBy { it.trigger }) {
                db.expansions().assign(trigger, rows.sortedBy { it.position }.map { it.target })
                progress.advance(rows.size)
            }
            for (p in data.phrases) {
                db.userBigrams().upsertAdd(p.prev, p.next, p.lang, p.count.coerceAtMost(MAX_IMPORT_COUNT), now)
                progress.advance()
            }
        }
    }

    /**
     * On the main thread, because the keyboard's change listener repaints views, and with
     * commit so a kill right after cannot lose them. The two counters go last: they are what
     * makes the running keyboard re-read its tables, so they must see finished ones.
     */
    @SuppressLint("ApplySharedPref") // commit: the run is over once this returns
    private fun writePrefs(app: Context, data: Backup.Data) {
        val p = PreferenceManager.getDefaultSharedPreferences(app)
        val e = p.edit()
        for (pref in data.prefs) {
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
        e.commit()
        p.edit()
            .putInt(Prefs.DICT_GENERATION, p.getInt(Prefs.DICT_GENERATION, 0) + 1)
            .putInt(Prefs.EXPANSION_GENERATION, p.getInt(Prefs.EXPANSION_GENERATION, 0) + 1)
            .commit()
    }

    /**
     * Where the pre-import snapshot lives.
     *
     * Internal storage, not a document the user picks: the snapshot is automatic, and a picker
     * would stand between the user and the import they asked for.
     */
    fun snapshotFile(context: Context): File = File(context.filesDir, SNAPSHOT_FILENAME)

    /**
     * Writes the keyboard as it stands, before an import replaces it. Into a side file and
     * then renamed, so a kill never leaves half a snapshot behind the undo button. Failure is
     * swallowed: a snapshot that cannot be written must not stop the import, and the restore
     * button then does not appear.
     */
    private fun writeSnapshot(app: Context) {
        val target = snapshotFile(app)
        val side = File(app.filesDir, "$SNAPSHOT_FILENAME.part")
        try {
            val data = collect(app, withPhrases = true)
            side.bufferedWriter().use { w ->
                for (line in Backup.encode(data)) {
                    w.write(line)
                    w.write(NEWLINE)
                }
            }
            if (!side.renameTo(target)) throw IOException("cannot rename $side")
        } catch (e: IOException) {
            side.delete()
            target.delete()
        } catch (e: RuntimeException) {
            side.delete()
            target.delete()
        }
    }

    /** Everything worth carrying, read off the main thread. */
    fun collect(context: Context, withPhrases: Boolean): Backup.Data {
        val p = PreferenceManager.getDefaultSharedPreferences(context)
        val prefRows = ArrayList<Backup.Pref>()
        for ((key, value) in p.all) {
            // A change counter, not a setting: copying it would leave the new device's
            // generation ahead of or behind its own data.
            if (key == Prefs.DICT_GENERATION || key == Prefs.EXPANSION_GENERATION) continue
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
        val db = KineticaDb.get(context)
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
            if (DictionaryStore.readInfo(context, lang) != null) base.add(lang)
        }
        val chords = db.chordShortcuts().all().map { Backup.Chord(it.chord, it.expansion) }
        val expansions = db.expansions().all().map {
            Backup.Expand(it.trigger, it.position, it.target)
        }
        return Backup.Data(prefRows, words, blocked, chords, expansions, phrases, base)
    }

    /** The ceiling on an imported count, the same one a personal-dictionary import uses. */
    const val MAX_IMPORT_COUNT = 10_000

    /** One slot: the last import is what is undoable. */
    private const val SNAPSHOT_FILENAME = "kinetica_pre_import_backup.txt"
    const val NEWLINE = "\n"
}

/**
 * How far a run has got, written by the run's thread and read by the screen. Pure, so its rules
 * are tested on the JVM: one run at a time, a count that never passes its total.
 */
class BackupProgress {

    enum class Phase { IDLE, READING, SAVING, WRITING }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    @Volatile var done: Int = 0
        private set

    @Volatile var total: Int = 0
        private set

    /** Runs ended so far, so a screen can tell an end it has not seen from one it has. */
    @Volatile var ended: Int = 0
        private set

    val running: Boolean get() = phase != Phase.IDLE

    /** A new run, refused while one is going. */
    @Synchronized
    fun begin(): Boolean {
        if (running) return false
        done = 0
        total = 0
        phase = Phase.READING
        return true
    }

    fun phase(p: Phase) {
        phase = p
    }

    fun writing(records: Int) {
        done = 0
        total = records.coerceAtLeast(0)
        phase = Phase.WRITING
    }

    fun advance(n: Int = 1) {
        done = (done + n).coerceAtMost(total)
    }

    @Synchronized
    fun end() {
        phase = Phase.IDLE
        ended++
    }

    /** 0 to 100, for the bar; 0 until the records are counted. */
    fun percent(): Int = if (total <= 0) 0 else (done * 100L / total).toInt()

    companion object {
        /** What a run writes, one per record: the dialog's denominator. */
        fun recordCount(data: Backup.Data): Int =
            data.words.size + data.blocked.size + data.chords.size + data.expansions.size + data.phrases.size
    }
}
