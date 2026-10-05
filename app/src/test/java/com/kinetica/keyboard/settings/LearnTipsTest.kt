package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Learn Kinetica tips. A tip that names a setting opens it, so its target must be
 * where the tree puts it: a tip pointing at a moved setting would open the wrong screen.
 */
class LearnTipsTest {

    private val xml = PrefsXml.read()
    private val where = PrefsXml.screenOf(xml)

    /** Each tip's key and whether its row is selectable. */
    private fun tips(): Map<String, Boolean> =
        Regex("""<Preference\s+android:key="(tip_[a-z_]+)"(.*?)/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to !it.groupValues[2].contains("""android:selectable="false"""") }

    @Test
    fun everyTargetIsWhereTheTreePutsIt() {
        for ((tip, target) in LearnTips.TARGETS) {
            assertTrue("$tip is not in the tree", where.containsKey(tip))
            assertTrue("$tip points at ${target.key}, which is not in the tree", where.containsKey(target.key))
            assertEquals("$tip's target ${target.key} is on another screen", where[target.key], target.screenKey)
        }
    }

    @Test
    fun aTipIsSelectableExactlyWhenItLeadsSomewhere() {
        val tips = tips()
        assertTrue("the tips must be found", tips.size >= 30)
        for ((tip, selectable) in tips) {
            assertEquals("$tip selectable=$selectable", selectable, tip in LearnTips.TARGETS)
        }
    }

    private fun strings(): Map<String, String> {
        val direct = Paths.get("src/main/res/values/strings.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/strings.xml")
        assumeTrue("strings.xml not found", Files.exists(p))
        val text = Files.newBufferedReader(p).use { it.readText() }
        return Regex("""<string name="(tip_[a-z_]+)"[^>]*>([^<]*)</string>""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun aTipIsShort() {
        val s = strings()
        for (tip in tips().keys) {
            val title = s.getValue("${tip}_title")
            val summary = s.getValue("${tip}_summary")
            assertTrue("$tip title: $title", title.length <= 40)
            assertTrue("$tip summary: $summary", summary.length <= 130)
            assertFalse("$tip has an em dash", (title + summary).contains('—'))
        }
    }
}
