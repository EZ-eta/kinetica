package com.kinetica.keyboard.settings

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import com.kinetica.keyboard.data.KineticaDb
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The whole-keyboard backup on its own screen: export, restore and the undo of the last import,
 * with the restore's progress. The run itself belongs to [BackupRun], so closing this screen never
 * stops it.
 */
class BackupSettingsActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout

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

    /** Redraws the buttons: the undo appears once there is a snapshot, all wait while a run goes. */
    private fun refresh() {
        container.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        // It carries every setting, every learned word in every language, the blocked words, the
        // chords and the edge swipes, so moving to a new phone loses nothing.
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
                isEnabled = !BackupRun.progress.running
                setOnClickListener { askPhrasesThenExport() }
            },
        )
        container.addView(
            Button(this).apply {
                text = getString(R.string.backup_import)
                isEnabled = !BackupRun.progress.running
                setOnClickListener {
                    pickBackup.launch(arrayOf("text/plain", "text/*", "*/*"))
                }
            },
        )
        // Only once there is something to undo: a restore button that is always there and usually
        // does nothing looks broken.
        val snapshot = BackupRun.snapshotFile(this)
        if (snapshot.exists()) {
            container.addView(
                Button(this).apply {
                    text = getString(
                        R.string.backup_restore,
                        java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                        ).format(java.util.Date(snapshot.lastModified())),
                    )
                    isEnabled = !BackupRun.progress.running
                    setOnClickListener { confirmRestoreSnapshot() }
                },
            )
        }
        }

    // ------------------------------------------------------- whole-keyboard backup

    /**
     * Phrases are the only part the export asks about.
     *
     * Learned word pairs are opt-in because a pair is a fragment of a sentence, and a backup file
     * can be copied anywhere. Leaving them out silently would lose data and putting them in
     * silently would undo the consent, so the export asks, unticked, every time.
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
                    createBackup.launch(Backup.filename(LocalDateTime.now()))
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
                        createBackup.launch(Backup.filename(LocalDateTime.now()))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun exportBackup(uri: Uri, withPhrases: Boolean) {
        io.execute {
            try {
                val data = BackupRun.collect(this, withPhrases)
                val dropped = Backup.unencodable(data)
                var lines = 0
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    for (line in Backup.encode(data)) {
                        w.write(line)
                        w.write(BackupRun.NEWLINE)
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

    private fun confirmRestoreSnapshot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_restore_title)
            .setMessage(R.string.backup_restore_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> restoreSnapshot() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreSnapshot() {
        if (BackupRun.start(this, BackupRun.Source.Snapshot, replace = true)) showProgress()
    }

    private fun runBackupImport(uri: Uri, replace: Boolean) {
        if (BackupRun.start(this, BackupRun.Source.Document(uri), replace)) showProgress()
    }

    // --------------------------------------------------------- import progress

    /** Up while a run is going; rebuilt after a rotation, because the run outlives the screen. */
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    /** The run end this screen last drew, so it refreshes once per run. */
    private var seenEnd = BackupRun.progress.ended

    private val progressTick = object : Runnable {
        override fun run() {
            val pr = BackupRun.progress
            if (pr.running) {
                drawProgress(pr)
                main.postDelayed(this, PROGRESS_TICK_MS)
            } else {
                progressDialog?.dismiss()
                progressDialog = null
                if (seenEnd != pr.ended) {
                    seenEnd = pr.ended
                    refresh()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (BackupRun.progress.running) showProgress() else progressTick.run()
    }

    override fun onStop() {
        main.removeCallbacks(progressTick)
        progressDialog?.dismiss()
        progressDialog = null
        super.onStop()
    }

    /**
     * Not cancelable: closing the screen no longer aborts a run, but the dialog is what tells
     * the user that leaving the app now could, before Android reclaims the process.
     */
    private fun showProgress() {
        if (progressDialog == null) {
            val pad = (20 * resources.displayMetrics.density).toInt()
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            val text = TextView(this)
            progressBar = bar
            progressText = text
            progressDialog = AlertDialog.Builder(this)
                .setTitle(R.string.backup_progress_title)
                .setView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad)
                        addView(text)
                        addView(bar)
                    },
                )
                .setCancelable(false)
                .show()
        }
        main.removeCallbacks(progressTick)
        progressTick.run()
    }

    private fun drawProgress(pr: BackupProgress) {
        val line = when (pr.phase) {
            BackupProgress.Phase.READING, BackupProgress.Phase.IDLE -> getString(R.string.backup_progress_reading)
            BackupProgress.Phase.SAVING -> getString(R.string.backup_progress_saving)
            BackupProgress.Phase.WRITING -> getString(R.string.backup_progress_writing, pr.done, pr.total)
        }
        progressText?.text = getString(R.string.backup_progress_keep_open, line)
        progressBar?.isIndeterminate = pr.phase != BackupProgress.Phase.WRITING
        progressBar?.progress = pr.percent()
    }

    private fun toastLater(resId: Int, vararg args: Any) {
        main.post {
            if (!isDestroyed) {
                Toast.makeText(this, getString(resId, *args), Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        /** About ten redraws a second: smooth enough, and nothing for the run's thread. */
        const val PROGRESS_TICK_MS = 100L
    }
}
