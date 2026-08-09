package com.kinetica.keyboard.settings

import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import java.io.IOException

/**
 * Renders THIRD_PARTY_NOTICES, which the build copies from the repo root
 * into the APK assets so the shipped screen can never drift from the file.
 * Plain local text on purpose: the licenses screen must not be the one
 * place the zero-network guarantee breaks.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(pad)
            setTextIsSelectable(true)
            text = try {
                assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                getString(R.string.licenses_missing)
            }
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val NOTICES_ASSET = "third_party_notices.txt"
    }
}
