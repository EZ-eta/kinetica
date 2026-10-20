package com.kinetica.keyboard.ime

import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.MapPrefs
import com.kinetica.keyboard.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comma key fits the field (#26): `@` in an email address, `/` in a web address, the comma
 * kept in its menu; the period's menu opens on domain endings in both. A key the user removed or
 * repurposed stays as they set it.
 */
class FieldKeysTest {

    private fun board() = KeyboardLayout(
        name = "qwerty", locale = "en_US",
        keys = listOf(
            Key("comma", KeyType.CHAR, ",", ",", 0.15f, 0.75f, 0.1f, 0.25f, alternates = listOf("_", "[")),
            Key("space", KeyType.SPACE, "", " ", 0.25f, 0.75f, 0.5f, 0.25f),
            Key("period", KeyType.CHAR, ".", ".", 0.75f, 0.75f, 0.1f, 0.25f, alternates = listOf("…")),
        ),
    )

    private fun comma(field: FieldKind, prefs: Map<String, Any> = emptyMap()): Key? =
        AlphaLayouts.build(board(), MapPrefs.config(prefs), field).keys.firstOrNull { it.id == "comma" }

    @Test
    fun anEmailFieldTypesTheAt() {
        val k = comma(FieldKind.EMAIL)!!
        assertEquals("@", k.output)
        assertEquals(",", k.alternates.first())
    }

    @Test
    fun aWebAddressTypesTheSlashWithTheColonBesideTheComma() {
        val k = comma(FieldKind.URL)!!
        assertEquals("/", k.output)
        assertEquals(listOf(",", ":"), k.alternates.take(2))
    }

    @Test
    fun plainTextAndNumbersKeepTheComma() {
        assertEquals(",", comma(FieldKind.TEXT)!!.output)
        assertEquals(",", comma(FieldKind.NUMERIC)!!.output)
    }

    @Test
    fun theUsersOwnCommaWins() {
        assertNull(comma(FieldKind.EMAIL, mapOf(Prefs.COMMA_MODE to "remove")))
        assertEquals("action:paste", comma(FieldKind.EMAIL, mapOf(Prefs.COMMA_MODE to "paste"))!!.output)
        assertEquals(",", comma(FieldKind.EMAIL, mapOf(Prefs.FIELD_KEYS to false))!!.output)
    }

    private fun period(field: FieldKind, prefs: Map<String, Any> = emptyMap()): Key? =
        AlphaLayouts.build(board(), MapPrefs.config(prefs), field).keys.firstOrNull { it.id == "period" }

    @Test
    fun anAddressOpensThePeriodsMenuOnDomainEndings() {
        for (field in listOf(FieldKind.URL, FieldKind.EMAIL)) {
            val k = period(field)!!
            assertEquals(field.name, ".", k.output)
            assertEquals(field.name, listOf(".com", ".net", ".org", "…"), k.alternates)
        }
    }

    @Test
    fun plainTextKeepsThePeriodsOwnMenu() {
        assertEquals(listOf("…"), period(FieldKind.TEXT)!!.alternates)
        assertEquals(listOf("…"), period(FieldKind.NUMERIC)!!.alternates)
        assertEquals(listOf("…"), period(FieldKind.URL, mapOf(Prefs.FIELD_KEYS to false))!!.alternates)
    }

    @Test
    fun theUsersOwnPeriodWins() {
        assertNull(period(FieldKind.URL, mapOf(Prefs.PERIOD_MODE to "remove")))
        val pasted = period(FieldKind.URL, mapOf(Prefs.PERIOD_MODE to "paste"))!!
        assertFalse(".com" in pasted.alternates)
    }

    @Test
    fun theEmojiEntryStaysFirstAndNothingIsListedTwice() {
        val emoji = LayoutMutations.EMOJI_ALTERNATE
        val board = board().let { b ->
            b.copy(keys = b.keys.map { if (it.id == "period") it.copy(alternates = listOf(emoji, ".net", "…")) else it })
        }
        val k = LayoutMutations.withPeriodAlternatesFirst(board, FieldKind.URL.domainEndings)
            .keys.first { it.id == "period" }
        assertEquals(listOf(emoji, ".com", ".net", ".org", "…"), k.alternates)
    }

    @Test
    fun theNumberPadOpensOnlyWhereAsked() {
        assertTrue(opensOnNumpad(restarting = false, enabled = true, numeric = true))
        assertFalse(opensOnNumpad(restarting = false, enabled = false, numeric = true))
        assertFalse(opensOnNumpad(restarting = true, enabled = true, numeric = true))
        assertFalse(opensOnNumpad(restarting = false, enabled = true, numeric = false))
    }
}
