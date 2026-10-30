package com.kinetica.keyboard.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kinetica.keyboard.R

/**
 * Picks a colour four ways that stay in step: a large swatch, hue / saturation / brightness
 * sliders whose tracks show what moving them does, swatches to tap, and the `#RRGGBB` field.
 *
 * The rules live in [ColourMix]. A slider or a swatch rewrites the field; typing never does, so a
 * half-typed code is left alone and only a whole one moves the sliders.
 */
object ColourDialog {

    fun show(context: Context, title: CharSequence?, initialHex: String, onPicked: (String) -> Unit) {
        val pick = ColourMix(HexColor.colorOr(initialHex, 0))
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        // A grey edge, so white reads on a light dialog and black on a dark one.
        val edge = 0x80808080.toInt()

        val previewShape = GradientDrawable().apply {
            cornerRadius = 10f * density
            setStroke(dp(1f), edge)
        }
        val preview = View(context).apply { background = previewShape }

        val field = EditText(context).apply {
            hint = context.getString(R.string.color_hex_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        class Slider(label: Int, max: Int) {
            val track = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0, 0)).apply {
                cornerRadius = 7f * density
                setStroke(dp(1f), edge)
            }
            val bar = SeekBar(context).apply {
                this.max = max
                // The gradient is the background, inset to the thumb's travel; a drawn progress
                // fill would hide half of it.
                val side = dp(14f)
                setPadding(side, 0, side, 0)
                background = InsetDrawable(track, side, dp(11f), side, dp(11f))
                progressDrawable = ColorDrawable(Color.TRANSPARENT)
            }
            val name = TextView(context).apply {
                text = context.getString(label)
                setPadding(dp(4f), dp(8f), 0, 0)
            }
        }
        val hue = Slider(R.string.color_pick_hue, ColourMix.HUE_MAX)
        val sat = Slider(R.string.color_pick_saturation, 100)
        val bright = Slider(R.string.color_pick_brightness, 100)

        val swatches = ArrayList<Pair<Int, GradientDrawable>>()
        var syncing = false

        fun refresh(fromField: Boolean, moved: SeekBar?) {
            syncing = true
            previewShape.setColor(pick.color)
            hue.track.colors = pick.hueTrack()
            sat.track.colors = pick.saturationTrack()
            bright.track.colors = pick.brightnessTrack()
            // The slider under the thumb keeps its own position: rounding it back would jitter.
            if (moved !== hue.bar) hue.bar.progress = pick.hueStep
            if (moved !== sat.bar) sat.bar.progress = pick.saturationStep
            if (moved !== bright.bar) bright.bar.progress = pick.brightnessStep
            if (!fromField) {
                field.setText(pick.hex)
                field.setSelection(field.text.length)
            }
            field.error = null
            val ring = field.currentTextColor
            for ((c, shape) in swatches) {
                if (c == pick.color) shape.setStroke(dp(3f), ring) else shape.setStroke(dp(1f), edge)
            }
            syncing = false
        }

        for ((s, move) in listOf(
            hue to pick::moveHue,
            sat to pick::moveSaturation,
            bright to pick::moveBrightness,
        )) {
            s.bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || syncing) return
                    move(progress)
                    refresh(fromField = false, moved = bar)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!syncing && pick.typed(s)) refresh(fromField = true, moved = null)
            }
        })

        val gap = dp(4f)
        val swatchRows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(4f))
        }
        ColourMix.SWATCHES.toList().chunked(SWATCHES_PER_ROW).forEach { rowColors ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in rowColors) {
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1f), edge)
                }
                swatches += c to dot
                row.addView(
                    View(context).apply {
                        background = dot
                        contentDescription = HexColor.format(c)
                        setOnClickListener {
                            pick.choose(c)
                            refresh(fromField = false, moved = null)
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(30f), 1f).apply { setMargins(gap, gap, gap, gap) },
                )
            }
            swatchRows.addView(row)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56f)))
            for (s in listOf(hue, sat, bright)) {
                addView(s.name)
                addView(s.bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f)))
            }
            addView(swatchRows)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        refresh(fromField = false, moved = null)

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            // Scrolls on a short screen or in landscape, where four controls and a keyboard do not fit.
            .setView(ScrollView(context).apply { addView(content) })
            // Set again once shown, so a refused value keeps the dialog open with its reason.
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (val r = HexColor.parse(field.text)) {
                HexColor.Result.Blank -> field.error = context.getString(R.string.color_hex_error_blank)
                is HexColor.Result.Invalid ->
                    field.error = context.getString(R.string.color_hex_error_bad, r.typed)
                is HexColor.Result.Valid -> {
                    onPicked(HexColor.format(r.color))
                    dialog.dismiss()
                }
            }
        }
    }

    private const val SWATCHES_PER_ROW = 8
}
