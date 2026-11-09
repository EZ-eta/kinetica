package com.kinetica.keyboard.onboarding

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.R
import com.kinetica.keyboard.onboarding.SetupSteps.Step
import com.kinetica.keyboard.settings.KeyboardPrefsFragment
import com.kinetica.keyboard.settings.Prefs
import com.kinetica.keyboard.settings.SettingsActivity

/**
 * The guided setup, one step at a time: turn the keyboard on, choose it, then languages, height,
 * automatic space, theme and the tutor, and a last page to try it. The order and where a launch
 * lands are [SetupSteps]; the choice steps are the settings' own rows ([KeyboardPrefsFragment.forSetup]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var step = Step.ENABLE
    private var pad = 0

    private lateinit var counter: TextView
    private lateinit var note: TextView
    private lateinit var plain: ScrollView
    private lateinit var host: FrameLayout
    private lateinit var back: Button
    private lateinit var skip: Button
    private lateinit var next: Button

    // The status lines of the step on screen, null when it has none.
    private var enableLine: TextView? = null
    private var selectLine: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        pad = (16 * resources.displayMetrics.density).toInt()

        counter = TextView(this)
        note = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        plain = ScrollView(this)
        // A resource id, not a generated one: after a rotation the restored step fragment looks
        // for the container it was in.
        host = FrameLayout(this).apply { id = R.id.setup_body }
        back = Button(this).apply {
            setText(R.string.setup_back)
            setOnClickListener { SetupSteps.back(step, isImeEnabled(), isImeSelected())?.let(::go) }
        }
        skip = Button(this).apply {
            setText(R.string.setup_skip)
            setOnClickListener { go(Step.DONE) }
        }
        next = Button(this).apply {
            setOnClickListener {
                if (SetupSteps.canGoNext(step, isImeEnabled(), isImeSelected())) go(SetupSteps.next(step))
            }
        }

        val body = FrameLayout(this).apply {
            addView(plain, matchParent())
            addView(host, matchParent())
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(back)
            addView(skip)
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(next)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(counter)
            addView(note)
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer)
        }
        setContentView(root)

        // Help and about's row starts the steps over; a rotation keeps the step it is on.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_RESTART, false) == true) {
            restart()
            return
        }
        val saved = prefs.getString(Prefs.SETUP_STEP, null)
        val finished = SetupSteps.finished(saved, prefs.getBoolean(Prefs.SETUP_DONE, false), isUpgrade())
        if (finished) prefs.edit().putBoolean(Prefs.SETUP_DONE, true).apply()
        go(SetupSteps.start(isImeEnabled(), isImeSelected(), saved, finished, isUpgrade()))
    }

    /** From the first choice, as the last page's button does: the system steps settle themselves. */
    private fun restart() {
        prefs.edit().putBoolean(Prefs.SETUP_DONE, false).apply()
        go(SetupSteps.start(isImeEnabled(), isImeSelected(), SetupSteps.FIRST_CHOICE.name, false, false))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // The keyboard picker is a dialog: choosing in it never pauses this activity, only takes and
    // returns the focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    /** Re-reads the system state: the status lines, Next, and enable or select done since. */
    private fun refresh() {
        val enabled = isImeEnabled()
        val selected = isImeSelected()
        enableLine?.text = stepLabel(R.string.onboarding_step1, enabled)
        selectLine?.text = stepLabel(R.string.onboarding_step2, selected)
        val settled = SetupSteps.settle(step, enabled, selected, prefs.getBoolean(Prefs.SETUP_DONE, false))
        if (settled != step) go(settled) else showButtons(enabled, selected)
    }

    private fun go(to: Step) {
        // A move from the focus callback can land after the state is saved; the saved step
        // reopens it on the next start.
        if (supportFragmentManager.isStateSaved) return
        step = to
        val edit = prefs.edit().putString(Prefs.SETUP_STEP, to.name)
        if (to == Step.DONE) edit.putBoolean(Prefs.SETUP_DONE, true)
        edit.apply()
        show(to)
    }

    private fun show(s: Step) {
        title = getString(titleOf(s))
        val n = SetupSteps.number(s)
        counter.text = if (n > 0) getString(R.string.setup_step, n, SetupSteps.NUMBERED.size) else ""
        counter.visibility = if (n > 0) View.VISIBLE else View.GONE
        val noteRes = noteOf(s)
        note.text = if (noteRes != 0) getString(noteRes) else ""
        note.visibility = if (noteRes != 0) View.VISIBLE else View.GONE

        enableLine = null
        selectLine = null
        val screen = s.prefScreen
        val shown = supportFragmentManager.findFragmentById(R.id.setup_body)
        if (screen != null) {
            plain.removeAllViews()
            plain.visibility = View.GONE
            host.visibility = View.VISIBLE
            // After a rotation the restored fragment is already this step's.
            if (shown?.tag != screen) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.setup_body, KeyboardPrefsFragment.forSetup(screen), screen)
                    .commit()
            }
        } else {
            if (shown != null) supportFragmentManager.beginTransaction().remove(shown).commit()
            host.visibility = View.GONE
            plain.visibility = View.VISIBLE
            plain.removeAllViews()
            plain.addView(plainBody(s))
        }
        showButtons(isImeEnabled(), isImeSelected())
    }

    private fun showButtons(enabled: Boolean, selected: Boolean) {
        val numbered = step != Step.DONE
        back.visibility = if (numbered && SetupSteps.back(step, enabled, selected) != null) View.VISIBLE else View.INVISIBLE
        skip.visibility = if (numbered) View.VISIBLE else View.GONE
        next.visibility = if (numbered) View.VISIBLE else View.GONE
        next.setText(if (step == Step.TUTOR) R.string.setup_finish else R.string.setup_next)
        next.isEnabled = SetupSteps.canGoNext(step, enabled, selected)
    }

    /** The steps that are not settings rows: the two system ones, the tutor and the last page. */
    private fun plainBody(s: Step): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun line(): TextView = TextView(this).apply { setPadding(0, pad, 0, 0) }.also { box.addView(it) }
        fun button(label: Int, onClick: () -> Unit) = box.addView(
            Button(this).apply {
                setText(label)
                setOnClickListener { onClick() }
            },
        )
        val openList = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val openPicker = {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val openTutor = { startActivity(Intent(this, TutorActivity::class.java)) }
        when (s) {
            Step.ENABLE -> {
                enableLine = line()
                button(R.string.onboarding_enable, openList)
            }
            Step.SELECT -> {
                selectLine = line()
                button(R.string.onboarding_select, openPicker)
            }
            Step.TUTOR -> button(R.string.setup_tutor_start, openTutor)
            else -> {
                enableLine = line()
                button(R.string.onboarding_enable, openList)
                selectLine = line()
                button(R.string.onboarding_select, openPicker)
                line().setText(R.string.onboarding_step3)
                box.addView(EditText(this).apply { setHint(R.string.onboarding_test_hint) })
                button(R.string.tutor_title, openTutor)
                button(R.string.onboarding_open_settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                button(R.string.setup_again) { restart() }
            }
        }
        enableLine?.text = stepLabel(R.string.onboarding_step1, isImeEnabled())
        selectLine?.text = stepLabel(R.string.onboarding_step2, isImeSelected())
        return box
    }

    private fun titleOf(s: Step): Int = when (s) {
        Step.ENABLE -> R.string.setup_enable_title
        Step.SELECT -> R.string.setup_select_title
        Step.LANGUAGES -> R.string.setup_languages_title
        Step.HEIGHT -> R.string.setup_height_title
        Step.SPACING -> R.string.setup_spacing_title
        Step.THEME -> R.string.setup_theme_title
        Step.TUTOR -> R.string.tutor_title
        Step.DONE -> R.string.setup_done_title
    }

    private fun noteOf(s: Step): Int = when (s) {
        Step.ENABLE -> R.string.setup_enable_note
        Step.SELECT -> R.string.setup_select_note
        Step.LANGUAGES -> R.string.setup_languages_note
        Step.HEIGHT -> R.string.setup_height_note
        Step.SPACING -> R.string.setup_spacing_note
        Step.THEME -> R.string.setup_theme_note
        Step.TUTOR -> R.string.setup_tutor_note
        Step.DONE -> 0
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    /**
     * Installed before this build: an update moves the update time off the install time. Not the
     * settings file, which the keyboard writes as soon as it first opens, before any setup.
     */
    private fun isUpgrade(): Boolean = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        info.lastUpdateTime != info.firstInstallTime
    } catch (e: PackageManager.NameNotFoundException) {
        false
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

    internal companion object {
        /** Set by Help and about's setup row (keyboard_prefs.xml): start over from the first choice. */
        const val EXTRA_RESTART = "com.kinetica.keyboard.onboarding.RESTART"
    }
}
