package com.kinetica.keyboard.settings

import com.kinetica.keyboard.keys.EdgeSwipeBindings
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EdgeSwipeBinding
import com.kinetica.keyboard.keys.EdgeSwipeDetector
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The one dropdown shared by the chord, edge-swipe and expansion editors. */
class ShortcutKindsTest {

    @Test
    fun everyKindReadsBackFromWhatItStores() {
        for (kinds in listOf(ShortcutKinds.chords, ShortcutKinds.edgeSwipes, ShortcutKinds.expansions)) {
            for (kind in kinds) {
                val field = when (kind) {
                    ShortcutKind.Text -> "hello"
                    ShortcutKind.CtrlKey -> "Del"
                    else -> ""
                }
                val stored = ShortcutKinds.encode(kind, field)!!
                assertEquals(kind to field, ShortcutKinds.decode(stored))
            }
        }
    }

    @Test
    fun ctrlPlusKeyStoresTheCombinationTheFieldNames() {
        assertEquals("combo:ctrl+c", ShortcutKinds.encode(ShortcutKind.CtrlKey, "c"))
        assertEquals("combo:ctrl+delete", ShortcutKinds.encode(ShortcutKind.CtrlKey, "DEL"))
        assertNull(ShortcutKinds.encode(ShortcutKind.CtrlKey, "ab"))
        assertNull(ShortcutKinds.encode(ShortcutKind.Text, ""))
        assertEquals(ShortcutKind.CtrlKey to "Shift+Left", ShortcutKinds.decode("combo:ctrl+shift+left"))
    }

    @Test
    fun eachEditorOffersWhatItCanRun() {
        assertTrue(ShortcutKind.CtrlKey in ShortcutKinds.expansions)
        assertTrue(ShortcutKind.Emoji in ShortcutKinds.edgeSwipes)
        assertFalse(ShortcutKind.Emoji in ShortcutKinds.chords)
        for (a in EditorAction.NOT_EXPANSION_TARGETS) assertFalse(ShortcutKind.Action(a) in ShortcutKinds.expansions)
        for (a in EditorAction.entries) assertTrue(ShortcutKind.Action(a) in ShortcutKinds.chords)
        assertEquals(EdgeSwipeBindings.ACTION_EMOJI, ShortcutKinds.encode(ShortcutKind.Emoji, ""))
    }

    @Test
    fun languageSwitchCanBeSelectedForAGestureWithoutAddingADefault() {
        val kind = ShortcutKind.Action(EditorAction.NEXT_LANGUAGE)
        assertTrue(kind in ShortcutKinds.edgeSwipes)
        assertFalse(ShortcutKinds.usesField(kind))
        assertEquals(com.kinetica.keyboard.R.string.action_next_language, ShortcutKinds.labelRes(kind))
        val output = ShortcutKinds.encode(kind, "")!!
        assertEquals("action:next_language", output)
        assertEquals(kind to "", ShortcutKinds.decode(output))

        val key = Key("e", KeyType.CHAR, "e", "e", 0.2f, 0f, 0.1f, 0.25f)
        val bindings = EdgeSwipeBindings(
            listOf(EdgeSwipeBinding(key.id, EdgeSwipeBinding.Direction.UP, output)),
        )
        val fired = EdgeSwipeDetector.detect(key, 4f, -42f, 4f, -42f, 1f, bindings)!!
        assertEquals(EditorAction.NEXT_LANGUAGE, EditorAction.of(fired))
        assertEquals("it", ActionRow.nextLanguage(listOf("en", "it"), "en"))
        assertTrue(EdgeSwipeBindings.DEFAULTS.bindings.none { it.output == output })
    }

    private fun strings(): Map<String, String> {
        val direct = Paths.get("src/main/res/values/strings.xml")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/values/strings.xml")
        assumeTrue("strings.xml not found", Files.exists(p))
        val xml = Files.newBufferedReader(p).use { it.readText() }
        return Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun everyChoiceIsAShortName() {
        // Short names ("Up arrow", "Page up", "DEL"), not sentences ("Presses the up arrow").
        val byId = com.kinetica.keyboard.R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val text = strings()
        val sentences = Regex("""^(Presses|Holds|Turns|Opens|Writes|Switches|Moves|Deletes|Copies|Cuts|Selects|Retypes|Expands|Pastes|Types|Inserts)\b""")
        for (kind in ShortcutKinds.edgeSwipes + ShortcutKinds.chords) {
            val label = text.getValue(byId.getValue(ShortcutKinds.labelRes(kind)))
            assertFalse("$kind reads as a sentence: $label", sentences.containsMatchIn(label))
            assertTrue("$kind is long: $label", label.length <= 24)
        }
    }
}
