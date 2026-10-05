package com.kinetica.keyboard.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R

/**
 * The project's Ko-fi link. The browser opens it, so the keyboard itself still
 * declares no network permission; with no browser the link is shown to copy.
 */
class ContributeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val fallback = TextView(this).apply {
            text = getString(R.string.contribute_no_browser, KO_FI_URL)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(TextView(this@ContributeActivity).apply { setText(R.string.contribute_text) })
            addView(
                Button(this@ContributeActivity).apply {
                    setText(R.string.contribute_button)
                    isAllCaps = false
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KO_FI_URL)))
                        } catch (e: ActivityNotFoundException) {
                            fallback.visibility = View.VISIBLE
                        }
                    }
                },
            )
            addView(fallback)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        /** The link the README publishes; `ContributeLinkTest` keeps the two the same. */
        const val KO_FI_URL = "https://ko-fi.com/ez_eta"
    }
}
