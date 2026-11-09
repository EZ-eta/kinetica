package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The settings screens' view wiring, which no JVM test can inflate, scanned as source like
 * [RowSyncSourceTest]. Read off disk: a fail-first check needs `--rerun-tasks`.
 */
class SettingsWiringSourceTest {

    private fun source(name: String): String {
        val direct = Paths.get("src/main/java/com/kinetica/keyboard/settings/$name")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/java/com/kinetica/keyboard/settings/$name")
        assumeTrue("$name not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return src.substring(start, src.indexOf("\n    }\n", start))
    }

    @Test
    fun theEditingPagesSlideIsMovedUpAfterItIsSeeded() {
        // A set saved before the page gets the slide up; one seeded with the slide down has it moved
        // once. In this order, so the move reads what the first seed wrote.
        val apply = body(source("EdgeSwipeDefaults.kt"), "fun applyTo(")
        val seed = apply.indexOf("seed(prefs, Prefs.EDIT_PAGE_SEEDED, EdgeSwipeBindings::withEditPage)")
        val up = apply.indexOf("seed(prefs, Prefs.EDIT_PAGE_UP_SEEDED, EdgeSwipeBindings::withEditPageUp)")
        assertTrue("both seeds must run, the move second", seed >= 0 && up > seed)
    }

    @Test
    fun theHomeScreenHasASearchRowAndOnlyIt() {
        // The toolbar's magnifier went unnoticed; a row that says Search is found. On home only:
        // the index walks the home tree, and a subscreen has no search.
        val create = body(source("KeyboardPrefsFragment.kt"), "override fun onCreatePreferences(")
        assertTrue("the search row must be home only", create.contains("if (rootKey == null && ") && create.contains("addSearchRow()"))
        val row = body(source("KeyboardPrefsFragment.kt"), "private fun addSearchRow()")
        assertTrue("the row must sit above the groups and open the search", row.contains("order = -1") && row.contains("openSearch()"))
        assertTrue("the row must open the toolbar's search", body(source("SettingsActivity.kt"), "fun openSearch()").contains("expandActionView()"))
    }

    @Test
    fun theSetupRowStartsTheStepsOver() {
        // The row's extra and the activity's must be the same string, and only a fresh start reads
        // it: after a rotation the setup stays on its step.
        val xml = PrefsXml.read()
        val name = Regex("""android:key="pref_setup_again"[\s\S]*?<extra android:name="([^"]+)" android:value="true"""").find(xml)?.groupValues?.get(1)
        val main = Files.readAllLines(mainActivity()).joinToString("\n")
        assertTrue("the setup row's extra is missing", name != null)
        assertTrue("MainActivity must read $name", main.contains("const val EXTRA_RESTART = \"$name\""))
        assertTrue("only a fresh start restarts", main.contains("savedInstanceState == null && intent?.getBooleanExtra(EXTRA_RESTART, false) == true"))
    }

    private fun mainActivity(): Path {
        val direct = Paths.get("src/main/java/com/kinetica/keyboard/onboarding/MainActivity.kt")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/java/com/kinetica/keyboard/onboarding/MainActivity.kt")
    }

    @Test
    fun onlyTheChangedScreenReadsDefaultsThroughTheRecorder() {
        // The recorder keeps no write: set on any other screen, its rows would stop saving.
        val src = source("KeyboardPrefsFragment.kt")
        val sets = Regex("""preferenceManager\.preferenceDataStore\s*=""").findAll(src).count()
        assertTrue("one place sets the data store", sets == 1)
        assertTrue(
            "the recorder is for the changed screen only",
            src.contains("if (rootKey == CHANGED_SCREEN) preferenceManager.preferenceDataStore = defaultsSeen"),
        )
        // Stored values come from the store itself, never from rows inflated with defaults.
        val rows = body(src, "private fun refreshChangedRows()")
        assertTrue(rows.contains("PreferenceManager.getDefaultSharedPreferences(ctx)") && rows.contains("prefs.all"))
    }

    @Test
    fun theTryFieldLearnsNothingAndNeverOpensTheKeyboardByItself() {
        // Practice in settings never reaches the personal words. The root
        // takes the first focus, and the window starts with the keyboard hidden, so opening a
        // screen does not pop the keyboard over it.
        val src = source("SettingsActivity.kt")
        val field = body(src, "private fun screenWithTryField()")
        assertTrue("the field must ask for no learning", field.contains("imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING"))
        assertTrue("the root must take the first focus", field.contains("isFocusableInTouchMode = true") && field.contains("requestFocus()"))
        assertTrue("the field and the container need resource ids", field.contains("id = R.id.settings_try_field") && field.contains("id = R.id.settings_body"))
        assertTrue("the settings key must know the field", field.contains("privateImeOptions = EditorState.TRY_FIELD_OPTION"))
        assertTrue("no screen may go to the window's content root", !src.contains("android.R.id.content"))
        val manifest = Files.readAllLines(manifest()).joinToString("\n")
        val entry = Regex("""<activity\s+android:name="\.settings\.SettingsActivity"[^>]*>""").find(manifest)?.value.orEmpty()
        assertTrue("the window must resize above the keyboard and start without it", entry.contains("adjustResize|stateHidden"))
    }

    private fun manifest(): Path {
        val direct = Paths.get("src/main/AndroidManifest.xml")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/AndroidManifest.xml")
    }

    @Test
    fun aRevealedRowOpensItsScreenExpanded() {
        // A row under Advanced has no list position until the group is expanded, so a search hit,
        // a tip or the keyboard's link would scroll to nothing and flash nothing.
        val open = body(source("SettingsActivity.kt"), "private fun openScreen(")
        assertTrue("openScreen must pass the row", open.contains("putString(KeyboardPrefsFragment.ARG_REVEAL"))
        val create = body(source("KeyboardPrefsFragment.kt"), "override fun onCreatePreferences(")
        assertTrue(
            "the screen must open expanded for a revealed row",
            create.contains("getString(ARG_REVEAL)") && create.contains("initialExpandedChildrenCount = Int.MAX_VALUE"),
        )
    }

    @Test
    fun theLookGroupsFollowTheSwitchAndHideWhatDoesNotApply() {
        // Theme and look: the night group only with the switch, a reveal opens a look group like an
        // orientation's, and a row that does nothing in the look as set is hidden.
        val src = source("KeyboardPrefsFragment.kt")
        val create = body(src, "override fun onCreatePreferences(")
        assertTrue(create.contains("wireLookGroups()"))
        val groups = body(src, "private fun wireLookGroups(")
        assertTrue(groups.contains("LookGroups.startsOpen(rowsKey, nightShown)"))
        assertTrue(groups.contains("showLookSets(prefs.getBoolean(Prefs.LOOK_SETS"))
        val show = body(src, "private fun showLookSets(")
        assertTrue(show.contains("findPreference<Preference>(LookGroups.NIGHT_HEADER)?.isVisible = on"))
        assertTrue(show.contains("findPreference<PreferenceCategory>(LookGroups.NIGHT_ROWS)?.isVisible = false"))
        assertTrue("the switch must show and hide the night group", body(src, "private fun wireLookSets(").contains("showLookSets(value == true)"))
        assertTrue(body(src, "private fun openGroupOf(").contains("SizeGroups.GROUPS + LookGroups.GROUPS"))
        val preview = body(src, "private fun wireThemePreview(")
        assertTrue(preview.contains("borderWidth?.isVisible = value == true"))
        assertTrue(preview.contains("trailHex?.isVisible = value == CUSTOM_TRAIL"))
        assertTrue(preview.contains("borderWidth?.isVisible = borders?.isChecked == true"))
        assertTrue(preview.contains("trailHex?.isVisible = trail?.value == CUSTOM_TRAIL"))
        val image = body(src, "private fun refreshBackdropRow(")
        assertTrue(image.contains("k(Prefs.BACKDROP_ALPHA))?.isVisible = set"))
        assertTrue(image.contains("k(Prefs.KEY_ALPHA))?.isVisible = set"))
        val entries = body(src, "private fun collectEntries(")
        assertTrue("night rows must not be found while the switch is off", entries.contains("LookGroups.NIGHT_ROWS) && !lookSetsOn()) continue"))
        assertTrue(entries.contains("getString(R.string.pref_look_night_title)"))
    }
}
