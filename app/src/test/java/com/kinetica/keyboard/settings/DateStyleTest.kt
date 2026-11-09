package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ime.ImeSources
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The date action writes the phone's own format, or ISO 8601 when the setting asks. */
class DateStyleTest {

    @Test
    fun thePhonesFormatIsTheDefaultAndAnUnknownValueKeepsIt() {
        assertEquals(DateStyle.SYSTEM, MapPrefs.config().dateStyle)
        assertEquals(DateStyle.SYSTEM, MapPrefs.config(mapOf(Prefs.DATE_STYLE to "rfc")).dateStyle)
        for (style in DateStyle.entries) {
            assertEquals(style, MapPrefs.config(mapOf(Prefs.DATE_STYLE to style.value)).dateStyle)
        }
    }

    @Test
    fun isoIsYearMonthDayPadded() {
        assertEquals("2025-12-31", DateStyle.iso(LocalDate.of(2025, 12, 31)))
        assertEquals("2027-01-09", DateStyle.iso(LocalDate.of(2027, 1, 9)))
    }

    @Test
    fun isoKeepsLatinDigitsWhateverThePhonesLanguage() {
        // A date written to sort and parse anywhere: Arabic or Persian digits would do neither.
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-ca-buddhist")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "2025-12-31", DateStyle.iso(LocalDate.of(2025, 12, 31)))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun theListOffersEveryStyleAndShipsTheCodeDefault() {
        val arrays = res("values/arrays.xml")
        val values = items(arrays, "date_style_values")
        assertEquals(DateStyle.entries.map { it.value }, values)
        assertEquals(values.size, items(arrays, "date_style_entries").size)
        assertEquals(values.size, items(res("values-it/arrays.xml"), "date_style_entries").size)
        val prefs = res("xml/keyboard_prefs.xml")
        val default = Regex("""android:key="${Prefs.DATE_STYLE}"[^>]*?android:defaultValue="([^"]+)"""")
            .find(prefs)?.groupValues?.get(1)
        assertEquals(Prefs.DEFAULT_DATE_STYLE, default)
        assertEquals(DateStyle.SYSTEM.value, Prefs.DEFAULT_DATE_STYLE)
    }

    @Test
    fun theDateActionReadsTheSetting() {
        // The write itself needs a live editor; the branch it takes is guarded here.
        val src = ImeSources.declaring("private fun insertNow(date: Boolean)").text
        assertTrue(src.contains("date && ime.config.dateStyle == DateStyle.ISO -> DateStyle.iso(LocalDate.now())"))
    }

    @Test
    fun theSettingTravelsInABackup() {
        assertTrue(Backup.carried(Prefs.DATE_STYLE))
    }

    private fun res(rel: String): String {
        val direct = Paths.get("src/main/res/$rel")
        val p: Path = if (Files.exists(direct)) direct else Paths.get("app/src/main/res/$rel")
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    private fun items(xml: String, name: String): List<String> {
        val block = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item>([^<]*)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }
}
