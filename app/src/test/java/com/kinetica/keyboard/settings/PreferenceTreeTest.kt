package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ime.DictationHandoff
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The shape of the settings tree, read off the shipped XML.
 *
 * The settings are nested screens, three levels at most, and nesting has one way to fail hard:
 * `android:dependency` is resolved against the current root and each subscreen is inflated as
 * its own root, so a dependent separated from its parent throws at inflation.
 * [everyDependencyLivesWithItsParent] guards that.
 *
 * Parsed as text, like `SymbolsLayoutTest`, because the question is about structure. The file
 * is read off disk, so Gradle does not treat it as an input and a fail-first check here needs
 * `--rerun-tasks`.
 */
class PreferenceTreeTest {

    private fun prefsXml(): String = PrefsXml.read()

    /** Every `android:key`, in file order. */
    private fun keys(xml: String): List<String> =
        Regex("""android:key="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()

    /**
     * The key of the nested `<PreferenceScreen>` each row sits in, or null for the top level.
     * A nested screen's own key is placed in the screen that holds it.
     *
     * A stack of open screens, because the tree is three levels deep; a screen's key is the
     * first `android:key` after its open tag.
     */
    private fun screenOf(xml: String): Map<String, String?> = walk(xml).first

    /** How many nested screens down each nested screen is: 1 for a top-level row. */
    private fun depthOf(xml: String): Map<String, Int> = walk(xml).second

    private fun walk(xml: String): Pair<Map<String, String?>, Map<String, Int>> = PrefsXml.walk(xml)

    @Test
    fun everyPreferenceIsInsideExactlyOneScreen() {
        val xml = prefsXml()
        val all = keys(xml)
        assertEquals("no key may appear twice", all.size, all.toSet().size)
        val placed = screenOf(xml)
        assertEquals("every key must be placed", all.toSet(), placed.keys)
    }

    @Test
    fun everyDependencyLivesWithItsParent() {
        // The crash: Preference.registerDependency resolves against the current root screen
        // and throws IllegalStateException when the parent is on a different one.
        val xml = prefsXml()
        val where = screenOf(xml)
        val blocks = xml.split(Regex("""(?=<\w)"""))
        var checked = 0
        for (b in blocks) {
            val dep = Regex("""android:dependency="([^"]+)"""").find(b) ?: continue
            val key = Regex("""android:key="([^"]+)"""").find(b)!!.groupValues[1]
            val parent = dep.groupValues[1]
            assertTrue("$key depends on $parent, which is not in the tree", where.containsKey(parent))
            assertEquals(
                "$key and its parent $parent must share a screen or the subscreen crashes",
                where[parent], where[key],
            )
            checked++
        }
        assertTrue("the five known dependencies must be found", checked >= 5)
    }

    @Test
    fun aNestedScreenCarriesNoDependency() {
        // A screen opened becomes the fragment's root, and its own dependency is looked up inside
        // it, where the parent never is: the Night look screen crashed on open this way.
        val screens = Regex("""<PreferenceScreen\b[^>]*>""").findAll(prefsXml()).map { it.value }.toList()
        val bad = screens.filter { "android:dependency=" in it }
            .map { Regex("""android:key="([^"]+)"""").find(it)?.groupValues?.get(1) }
        assertEquals("a screen with a dependency crashes when opened", emptyList<String?>(), bad)
    }

    @Test
    fun theVersionRowSitsInHelpAndAbout() {
        // The Android settings guide keeps the version off the overview; Help and about shows it on
        // its own row, and showVersion finds it on whichever screen holds it.
        assertEquals("pref_group_learn", screenOf(prefsXml())["pref_version"])
    }

    @Test
    fun theEverydayScreensOpenFromTheHomeScreen() {
        // The suggestion bar sat three levels down under Typing and voice input under Shortcuts;
        // a basic user looking for either did not find it.
        val where = screenOf(prefsXml())
        for (k in listOf("pref_group_bar", DictationHandoff.SCREEN)) assertEquals("$k must open from home", null, where[k])
    }

    @Test
    fun theLetterArrangementSitsWithTheLanguages() {
        // A language change sets it, so it is read beside the language that chose it.
        assertEquals("pref_group_languages", screenOf(prefsXml())[Prefs.KEY_ARRANGEMENT])
    }

    @Test
    fun thePreviewsLeadTheirScreens() {
        // A preview below the fold shows nothing while the rows above it change; first, it stays
        // in view and outside the Advanced rows. The theme previews lead their look's group.
        val xml = prefsXml()
        val headers = PrefsXml.categories(xml)
        val where = screenOf(xml).filterKeys { it !in headers }
        assertEquals("pref_bar_preview", where.entries.first { it.value == "pref_group_bar" }.key)
        assertEquals("pref_theme_preview", PrefsXml.keysIn(PrefsXml.category(xml, LookGroups.DAY_ROWS)).first())
        assertEquals("pref_theme_preview_night", PrefsXml.keysIn(PrefsXml.category(xml, LookGroups.NIGHT_ROWS)).first())
    }

    @Test
    fun theThemePreviewSharesAScreenWithWhatItShows() {
        // wireThemePreview returns early when the preview is absent, taking the colour, mode,
        // brightness and border listeners with it. The failure is a dead swatch, not a crash.
        val where = screenOf(prefsXml())
        for (k in listOf(
            "pref_theme_color_hex", "pref_theme_mode", "pref_theme_brightness",
            "pref_key_borders", "pref_key_border_width",
        )) {
            assertEquals("$k must sit with the preview", where["pref_theme_preview"], where[k])
        }
    }

    @Test
    fun theNightLookGroupMirrorsTheDayLook() {
        // The night look is the day look's rows under their twins: a row added to one and not the
        // other leaves a look setting with no row, or a row the keyboard never reads.
        val xml = prefsXml()
        val dayRows = PrefsXml.keysIn(PrefsXml.category(xml, LookGroups.DAY_ROWS))
        val nightRows = PrefsXml.keysIn(PrefsXml.category(xml, LookGroups.NIGHT_ROWS))
        val expected = (Prefs.NIGHT_TWINS - setOf(Prefs.BACKDROP, Prefs.BACKDROP_CROP, Prefs.BACKDROP_CROP_LANDSCAPE)) +
            listOf("pref_theme_preview", "pref_backdrop_image")
        assertEquals(expected.toSet(), dayRows.toSet())
        assertEquals("the same rows in the same order", dayRows.map(Prefs::nightTwin), nightRows)
        // Same row, same defaults and bounds: only the key, and the border width's dependency, differ.
        fun dep(k: String) = "android:dependency=\"$k\""
        for (k in dayRows) {
            val day = PrefsXml.openTag(xml, k).replace(dep(Prefs.KEY_BORDERS), dep(Prefs.nightTwin(Prefs.KEY_BORDERS)))
            val night = PrefsXml.openTag(xml, Prefs.nightTwin(k)).replace("\"${Prefs.nightTwin(k)}\"", "\"$k\"")
            assertEquals(k, day, night)
        }
    }

    @Test
    fun theLookScreenIsBothLooksThenTheTwoGroups() {
        // What both looks share sits on top with the switch, then each look under its header, as
        // Size and layout puts both orientations, then each.
        val xml = prefsXml()
        val look = screenOf(xml).filterValues { it == "pref_group_look" }.keys.toList()
        assertEquals(
            listOf("pref_look_both", LookGroups.DAY_HEADER, LookGroups.DAY_ROWS, LookGroups.NIGHT_HEADER, LookGroups.NIGHT_ROWS),
            look.filter { it in PrefsXml.categories(xml) || it in LookGroups.GROUPS.keys },
        )
        assertEquals(
            listOf(Prefs.LOOK_SETS, Prefs.KEEP_TRAIL, Prefs.ZEN_MODE),
            PrefsXml.keysIn(PrefsXml.category(xml, "pref_look_both")),
        )
    }

    @Test
    fun theShortcutChoosersShareAScreenWithTheBarHeight() {
        // The bar's height row is where anyone looks for a setting about the bar; under Typing
        // and predictions it could not be found. The ?123 chooser sits beside the bar's so the
        // two sets read as a pair.
        val where = screenOf(prefsXml())
        assertEquals(where["pref_suggestion_bar_dp"], where["pref_bar_actions"])
        assertEquals(where["pref_bar_actions"], where["pref_menu_actions"])
    }

    @Test
    fun noScreenIsMoreThanThreeLevelsDown() {
        // Progressive disclosure: the everyday settings one level down, the expert ones a level
        // below that, and nothing deeper.
        val deep = depthOf(prefsXml()).filterValues { it > 2 }
        assertTrue("screens too deep: $deep", deep.isEmpty())
        assertTrue("the nested screens must be found", depthOf(prefsXml()).values.any { it == 2 })
    }

    @Test
    fun everySizeSliderSharesAScreenWithAPreview() {
        // The fragment makes a size slider update continuously only when its screen holds a
        // size preview, so a slider moved away from one stops showing its effect live.
        val where = screenOf(prefsXml())
        val previews = listOf("pref_size_preview", "pref_size_preview_landscape", "pref_bar_preview").map { where[it] }.toSet()
        for (k in listOf(
            "pref_keyboard_height_pct", "pref_keyboard_height_pct_landscape", "pref_suggestion_bar_dp",
            "pref_side_pad_dp", "pref_bottom_pad_dp", "pref_drag_handle_dp", "pref_landscape_split_gap_pct",
            "pref_home_row_spread",
        ) + Prefs.LANDSCAPE_TWINS.map(Prefs::landscapeTwin)) {
            assertTrue("$k is on ${where[k]}, which has no size preview", where.containsKey(k) && where[k] in previews)
        }
    }

    @Test
    fun noSubmenuHoldsASingleRow() {
        // A submenu with one row is a tap that shows nothing new: "More language options" held
        // only the language mode once Mix and Follow became one list.
        val xml = prefsXml()
        val headers = PrefsXml.categories(xml)
        val rows = screenOf(xml).filterKeys { it !in headers }.values.filterNotNull().groupingBy { it }.eachCount()
        val single = depthOf(xml).keys.filter { (rows[it] ?: 0) == 1 }
        assertTrue("submenus with one row: $single", single.isEmpty())
    }

    @Test
    fun theLanguageModeSitsWithTheEnabledLanguages() {
        // It chooses how the enabled languages share the bar, so it is read beside them.
        val where = screenOf(prefsXml())
        assertEquals("pref_group_languages", where[Prefs.LANGUAGE_MODE])
        assertEquals(where[Prefs.LANGUAGE_MODE], where["pref_enabled_languages"])
    }

    @Test
    fun theScreensTheKeyboardOpensStillExist() {
        // The long-press menu's "edit" opens this screen by key, a letter's or a symbol's.
        assertTrue(depthOf(prefsXml()).containsKey(SettingsSynonyms.LONGPRESS_GROUP))
        assertTrue(depthOf(prefsXml()).containsKey(SettingsSynonyms.SYMBOL_PAGES_GROUP))
    }

    @Test
    fun practiseTwoThumbsLeadsHelpAndTheTipsHaveTheirOwnScreen() {
        // The tutor on top; the tips, 33 rows, on a screen of their own so the version and the
        // licenses stay in reach.
        val where = screenOf(prefsXml())
        val help = where.filterValues { it == "pref_group_learn" }.keys
        assertEquals("pref_tutor_screen", help.first())
        assertTrue("the tips must be found", where.filterValues { it == "pref_group_tips" }.keys.count { it.startsWith("tip_") } >= 30)
    }

    @Test
    fun learnKineticaAndContributeOpenFromHome() {
        // Under Help and about neither was found: one level down, a row opens each.
        val xml = prefsXml()
        val where = screenOf(xml)
        for (k in listOf("pref_group_tips", "pref_contribute_screen")) assertEquals("$k must open from home", null, where[k])
        assertTrue("contribute shows a heart", PrefsXml.openTag(xml, "pref_contribute_screen").contains("@drawable/ic_settings_contribute"))
    }

    @Test
    fun theTopLevelStaysShortEnoughToScan() {
        // A flat list of 52 rows could not be navigated; 14 in two groups, each with an icon, scan.
        val xml = prefsXml()
        val headers = PrefsXml.categories(xml)
        val top = screenOf(xml).filterValues { it == null }.filterKeys { it !in headers }
        assertTrue("top level is ${top.size} rows: ${top.keys}", top.size <= 14)
    }

    @Test
    fun everyHomeRowSaysWhatIsInside() {
        // An overview, not a table of contents: each home row names what it holds, so a basic
        // user finds a setting without opening every screen.
        val xml = prefsXml()
        val headers = PrefsXml.categories(xml)
        val home = screenOf(xml).filterValues { it == null }.keys.filter { it !in headers }
        for (key in home) {
            val tag = PrefsXml.openTag(xml, key)
            assertTrue("$key has no summary", tag.contains("android:summary="))
            // An icon per row, so the list scans by shape before it is read.
            val icon = Regex("""android:icon="@drawable/(ic_settings_\w+)"""").find(tag)?.groupValues?.get(1)
            assertTrue("$key has no home icon", icon != null)
            val drawable = PrefsXml.resource("drawable/$icon.xml")
            assertTrue("$icon.xml is missing", Files.exists(drawable))
        }
        assertEquals("two groups on the home screen", setOf("pref_home_everyday", "pref_home_more"), headers.filter { screenOf(xml)[it] == null }.toSet())
    }

    /**
     * The rows a collapsed screen shows before its Advanced row, as androidx picks them: in order,
     * while fewer plain rows than the count have been taken; a nested screen's row takes its place
     * without counting.
     */
    private fun shownBeforeAdvanced(xml: String, screen: String, count: Int): List<String> {
        val nested = depthOf(xml).keys
        val rows = screenOf(xml).filterValues { it == screen }.keys
        val shown = ArrayList<String>()
        var counted = 0
        for (k in rows) {
            if (counted >= count) break
            shown.add(k)
            if (k !in nested) counted++
        }
        return shown
    }

    @Test
    fun longScreensShowTheirBasicsAndFoldTheRestUnderAdvanced() {
        // Everyday rows first, the rest behind androidx's Advanced row; two levels of disclosure,
        // so only screens one level down fold, and none holding a category (its header would be
        // left alone above nothing).
        val xml = prefsXml()
        val folded = Regex("""<PreferenceScreen\b[^>]*?android:key="([^"]+)"[^>]*?app:initialExpandedChildrenCount="(\d+)"""")
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(setOf("pref_group_typing", "pref_group_bar", "pref_group_keys"), folded.keys)
        val headers = PrefsXml.categories(xml)
        val nested = depthOf(xml).keys
        for ((screen, count) in folded) {
            assertEquals("$screen folds one level down", 1, depthOf(xml)[screen])
            val rows = screenOf(xml).filterValues { it == screen }.keys
            assertTrue("$screen holds a category", rows.none { it in headers })
            assertTrue("$screen shows everything, no Advanced row", rows.count { it !in nested } > count)
        }
        val basics = mapOf(
            "pref_group_typing" to listOf(Prefs.AUTOCORRECT_LEVEL, "pref_group_spacing", "pref_next_word", "pref_group_learning", Prefs.VIBRATION),
            "pref_group_bar" to listOf("pref_bar_preview", Prefs.SUGGESTION_BAR_DP, "pref_recent_words", "pref_bar_actions"),
            "pref_group_keys" to listOf("pref_number_row", "pref_group_longpress", "pref_comma_mode", "pref_comma_custom", "pref_period_mode", "pref_period_custom"),
        )
        for ((screen, want) in basics) {
            val shown = shownBeforeAdvanced(xml, screen, folded.getValue(screen))
            for (k in want) assertTrue("$k must show on $screen before Advanced: $shown", k in shown)
        }
    }

    @Test
    fun theWalkerClosesASelfClosingScreenAndSeesCategories() {
        // Read key by key, a `<PreferenceScreen .../>` never closed and every later row landed in it.
        val xml = """
            <PreferenceScreen>
              <PreferenceCategory android:key="head" android:title="t">
                <PreferenceScreen android:key="empty" android:title="e"/>
                <SwitchPreferenceCompat android:key="after" />
              </PreferenceCategory>
              <PreferenceScreen android:key="sub" android:title="s">
                <SwitchPreferenceCompat android:key="inside" />
              </PreferenceScreen>
            </PreferenceScreen>
        """.trimIndent()
        val (where, depth) = PrefsXml.walk(xml)
        assertEquals(null, where["after"])
        assertEquals(null, where["empty"])
        assertEquals("sub", where["inside"])
        assertEquals(mapOf("empty" to 1, "sub" to 1), depth)
        assertEquals(setOf("head"), PrefsXml.categories(xml))
    }
}

/** The shipped settings XML, read off disk, and its screens; shared with [LearnTipsTest]. */
internal object PrefsXml {

    fun read(): String {
        val direct = Paths.get("src/main/res/xml/keyboard_prefs.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/xml/keyboard_prefs.xml")
        assumeTrue("keyboard_prefs.xml not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    /**
     * Each key's screen (null: the top level), and each nested screen's depth.
     *
     * An open tag is read whole, so a screen's key is the one in its own tag and a self-closing
     * screen closes where it opens; reading keys one by one put every later row inside it.
     */
    fun walk(xml: String): Pair<Map<String, String?>, Map<String, Int>> {
        val out = LinkedHashMap<String, String?>()
        val depth = LinkedHashMap<String, Int>()
        val stack = ArrayList<String?>()
        val token = Regex("""</PreferenceScreen>|<PreferenceScreen\b([^>]*?)(/?)>|android:key="([^"]+)"""")
        for (m in token.findAll(xml)) {
            when {
                m.value.startsWith("</") -> stack.removeAt(stack.size - 1)
                m.value.startsWith("<") -> {
                    // The first open tag is the root, which has no key.
                    if (stack.isEmpty()) {
                        stack.add(null)
                        continue
                    }
                    val key = KEY.find(m.groupValues[1])?.groupValues?.get(1)
                        ?: throw AssertionError("a nested screen without a key: ${m.value}")
                    out[key] = stack.last()
                    depth[key] = stack.size
                    if (m.groupValues[2] != "/") stack.add(key)
                }
                else -> out[m.groupValues[3]] = stack.last()
            }
        }
        return out to depth
    }

    /** The keys of the categories: headers, not rows, so no row count takes them. */
    fun categories(xml: String): Set<String> =
        Regex("""<PreferenceCategory\b[^>]*?android:key="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    private val KEY = Regex("""android:key="([^"]+)"""")

    /** A file under `src/main/res`, from either working directory. */
    fun resource(path: String): Path {
        val direct = Paths.get("src/main/res/$path")
        return if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$path")
    }

    /** The opening tag of the element holding [key], whitespace folded. */
    fun openTag(xml: String, key: String): String {
        val at = xml.indexOf("android:key=\"$key\"")
        require(at >= 0) { "$key not in the XML" }
        val start = xml.lastIndexOf('<', at)
        return xml.substring(start, xml.indexOf('>', at) + 1).split(Regex("\\s+")).joinToString(" ")
    }

    fun screenOf(xml: String): Map<String, String?> = walk(xml).first

    /** The text of the category [key], from its open tag to its close. */
    fun category(xml: String, key: String): String {
        val start = Regex("""<PreferenceCategory\s+android:key="$key"""").find(xml)?.range?.first
        require(start != null) { "no category $key" }
        return xml.substring(start, xml.indexOf("</PreferenceCategory>", start))
    }

    /** Every key in [xml], in order, the first (its own) excluded when it opens with a category. */
    fun keysIn(xml: String): List<String> =
        KEY.findAll(xml).map { it.groupValues[1] }.toList().let { if (xml.startsWith("<PreferenceCategory")) it.drop(1) else it }
}
