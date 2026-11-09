package com.kinetica.keyboard.settings

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.BackdropCrop
import com.kinetica.keyboard.ui.BackdropCropView

/**
 * Where the picked image is fitted to the keyboard: a frame of the portrait keyboard's shape and
 * one of the landscape keyboard's, each moved and pinched over the image. Save keeps both cuts
 * and, after a new pick, makes the pending image the one in use; leaving any other way drops it.
 */
class BackdropCropActivity : AppCompatActivity() {

    private var pending = false
    private var night = false
    private var saved = false
    private lateinit var crop: BackdropCropView
    private val frames = arrayOfNulls<FloatArray>(2)
    private var shown = PORTRAIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.backdrop_crop_title)
        pending = intent.getBooleanExtra(EXTRA_PENDING, false)
        night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        val file = if (pending) BackdropStore.pendingFile(this) else BackdropStore.file(this, night)
        val image = try {
            BitmapFactory.decodeFile(file.path)
        } catch (e: OutOfMemoryError) {
            null
        }
        if (image == null) {
            Toast.makeText(this, R.string.backdrop_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        for (i in listOf(PORTRAIT, LANDSCAPE)) {
            // A new image opens on its centre; an adjustment on the cut already chosen.
            val stored = if (pending) null else BackdropCrop.parse(LookSets.view(prefs, night).getString(CUT_KEYS[i], ""))
            frames[i] = savedInstanceState?.getFloatArray(CUT_KEYS[i])
                ?: BackdropCrop.opening(stored, image.width, image.height, keyboardAspect(i == LANDSCAPE))
        }
        shown = savedInstanceState?.getInt(SHOWN_KEY)
            ?: if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) LANDSCAPE else PORTRAIT

        crop = BackdropCropView(this, image).apply { frame = frames[shown]!! }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val hint = TextView(this).apply {
            setText(R.string.backdrop_crop_hint)
            setPadding(pad, pad, pad, pad / 2)
        }
        val which = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(pad, 0, pad, 0)
            addView(RadioButton(context).apply { id = PORTRAIT_ID; setText(R.string.pref_size_portrait_title) })
            addView(RadioButton(context).apply { id = LANDSCAPE_ID; setText(R.string.pref_size_landscape_title) })
            check(if (shown == LANDSCAPE) LANDSCAPE_ID else PORTRAIT_ID)
            setOnCheckedChangeListener { _, id -> show(if (id == LANDSCAPE_ID) LANDSCAPE else PORTRAIT) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad / 2, pad, pad)
            addView(
                Button(context).apply { setText(android.R.string.cancel); setOnClickListener { finish() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                Button(context).apply { setText(R.string.backdrop_crop_save); setOnClickListener { save(image.width, image.height) } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(hint)
                addView(which)
                addView(crop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(buttons)
            },
        )
    }

    private fun show(i: Int) {
        if (i == shown) return
        frames[shown] = crop.frame
        shown = i
        crop.frame = frames[i]!!
    }

    private fun save(w: Int, h: Int) {
        frames[shown] = crop.frame
        if (pending && !BackdropStore.commitPending(this, night)) {
            Toast.makeText(this, R.string.backdrop_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val e = PreferenceManager.getDefaultSharedPreferences(this).edit()
        for (i in listOf(PORTRAIT, LANDSCAPE)) {
            e.putString(look(CUT_KEYS[i]), BackdropCrop.format(BackdropCrop.fractionOf(frames[i]!!, w, h)))
        }
        // A new file needs a new stamp for the keyboard to read it; a new cut alone does not.
        if (pending) e.putString(look(Prefs.BACKDROP), System.currentTimeMillis().toString())
        e.apply()
        saved = true
        finish()
    }

    /**
     * The keyboard's width over its height in each orientation, through the same arithmetic the
     * size preview uses: handle, bar, board and the gap below. The keyboard trims the cut to its
     * own shape if a later resize changes it.
     */
    private fun keyboardAspect(landscape: Boolean): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val dm = resources.displayMetrics
        val short = minOf(dm.widthPixels, dm.heightPixels)
        val long = maxOf(dm.widthPixels, dm.heightPixels)
        val config = KeyboardConfig.from(prefs, landscape)
        val w = if (landscape) long else short
        val h = if (landscape) short else long
        val pct = if (landscape) config.heightPctLandscape else config.heightPct
        val board = KeyboardHeights.boardPx(KeyboardHeights.targetPx(h, dm.density, pct), config.numberRow)
        val chrome = (KeyboardHeights.barDp(config.suggestionBarDp, config.recentWords) +
            config.dragHandleDp + config.bottomPadDp) * dm.density
        return w / (board + chrome).coerceAtLeast(1f)
    }

    /** [key] in the look this screen edits. */
    private fun look(key: String): String = if (night) Prefs.nightTwin(key) else key

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::crop.isInitialized) return
        frames[shown] = crop.frame
        for (i in listOf(PORTRAIT, LANDSCAPE)) frames[i]?.let { outState.putFloatArray(CUT_KEYS[i], it) }
        outState.putInt(SHOWN_KEY, shown)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        // Back, Cancel or the up arrow after a new pick: the image in use stays as it was.
        if (isFinishing && pending && !saved) BackdropStore.dropPending(this)
        super.onDestroy()
    }

    companion object {
        /** True when the image to fit is a new pick, still in the pending file. */
        const val EXTRA_PENDING = "pending"

        /** True when the image is the night look's. */
        const val EXTRA_NIGHT = "night"
        private const val PORTRAIT = 0
        private const val LANDSCAPE = 1
        private const val PORTRAIT_ID = 1
        private const val LANDSCAPE_ID = 2
        private const val SHOWN_KEY = "shown"
        private val CUT_KEYS = arrayOf(Prefs.BACKDROP_CROP, Prefs.BACKDROP_CROP_LANDSCAPE)
    }
}
