package com.kinetica.keyboard.onboarding

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import com.kinetica.keyboard.settings.SettingsActivity

/**
 * Three-step enable flow: system-enable the IME, pick it as current keyboard,
 * then try it in a test field. Step status is re-read from Settings.Secure on
 * every resume.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var step1: TextView
    private lateinit var step2: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
        }

        val title = TextView(this).apply {
            text = getString(R.string.onboarding_title)
            textSize = 24f
        }
        root.addView(title)

        step1 = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(step1)
        root.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_enable)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            },
        )

        step2 = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(step2)
        root.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_select)
                setOnClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                }
            },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_step3)
                setPadding(0, pad, 0, 0)
            },
        )
        root.addView(
            EditText(this).apply {
                hint = getString(R.string.onboarding_test_hint)
            },
        )

        root.addView(
            Button(this).apply {
                text = getString(R.string.tutor_title)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, TutorActivity::class.java))
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_open_settings)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            },
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        step1.text = stepLabel(R.string.onboarding_step1, isImeEnabled())
        step2.text = stepLabel(R.string.onboarding_step2, isImeSelected())
    }

    private fun stepLabel(labelRes: Int, done: Boolean): String =
        if (done) getString(R.string.onboarding_step_done, getString(labelRes))
        else getString(labelRes)

    // Settings.Secure.ENABLED_INPUT_METHODS throws a SecurityException on targetSdk > 33,
    // so the enabled check uses the InputMethodManager list, which needs no permission.
    // DEFAULT_INPUT_METHOD is still readable but guarded, so a future restriction reads as
    // "not selected" instead of a crash.
    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isImeSelected(): Boolean {
        val current = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: SecurityException) {
            return false
        } ?: return false
        return current.startsWith(packageName)
    }
}
