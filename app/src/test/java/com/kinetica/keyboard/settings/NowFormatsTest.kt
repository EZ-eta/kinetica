/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.settings

import com.kinetica.keyboard.ime.ImeSources
import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.EditorAction
import com.kinetica.keyboard.settings.NowPattern.Problem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The date, time and stamp actions: presets, a typed pattern, its warnings, and the stored choice. */
class NowFormatsTest {

    private val evening = LocalDateTime.of(2025, 12, 31, 23, 59, 30)
    private val morning = LocalDateTime.of(2027, 1, 9, 7, 5, 3)

    @Test
    fun everyPresetWritesItsShape() {
        val shapes = mapOf(
            NowPreset.DATE_ISO to "2025-12-31",
            NowPreset.DATE_SPACED to "2025 12 31",
            NowPreset.DATE_DOTTED to "31.12.2025",
            NowPreset.DATE_COMPACT to "20251231",
            NowPreset.TIME_HM to "23:59",
            NowPreset.TIME_HMS to "23:59:30",
            NowPreset.TIME_COMPACT to "235930",
            NowPreset.STAMP_ISO to "2025-12-31 23:59:30",
            NowPreset.STAMP_COMPACT to "20251231-235930",
        )
        for ((preset, text) in shapes) assertEquals(preset.name, text, NowPattern.format(preset.pattern!!, evening))
        assertEquals("2027-01-09 07:05:03", NowPattern.format(NowPreset.STAMP_ISO.pattern!!, morning))
    }

    @Test
    fun yearAndDayAreTheCalendarsNotTheWeeksOrTheYears() {
        // DateTimeFormatter's YYYY is the week year and DD the day of the year: 2024-12-30 would
        // read 2025-12-365 there.
        val turn = LocalDateTime.of(2024, 12, 30, 0, 0, 0)
        assertEquals("2024-12-30", NowPattern.format("YYYY-MM-DD", turn))
        assertEquals("2024-12-30", NowPattern.format("yyyy-MM-dd", turn))
        assertEquals("24 12 30", NowPattern.format("YY MM DD", turn))
        assertEquals("9/1/27", NowPattern.format("D/M/YY", morning))
    }

    @Test
    fun monthIsCapitalMinuteIsSmall() {
        assertEquals("12 59", NowPattern.format("MM mm", evening))
    }

    @Test
    fun twelveHourClockWithItsMark() {
        assertEquals("11:59 PM", NowPattern.format("hh:mm A", evening))
        assertEquals("7:05 am".uppercase(), NowPattern.format("h:mm a", morning).uppercase())
        val midnight = LocalDateTime.of(2025, 1, 1, 0, 0, 0)
        val noon = LocalDateTime.of(2025, 1, 1, 12, 0, 0)
        assertEquals("12 AM 00", NowPattern.format("hh A HH", midnight))
        assertEquals("12 PM 12", NowPattern.format("hh A HH", noon))
        assertEquals("7 07", NowPattern.format("H HH", morning))
    }

    @Test
    fun quotedTextIsWrittenAsItStands() {
        assertEquals("day 31, it's 23h", NowPattern.format("'day' D, 'it''s' HH'h'", evening))
        assertNull(NowPattern.problem("'day' D, 'it''s' HH'h'"))
    }

    @Test
    fun latinDigitsWhateverThePhonesLanguage() {
        // A stamp written to sort and parse anywhere: Arabic or Persian digits would do neither.
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-ca-buddhist")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "2025-12-31 23:59:30", NowPattern.format(NowPreset.STAMP_ISO.pattern!!, evening))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun aTypedPatternIsWarnedAbout() {
        assertEquals(Problem.EMPTY, NowPattern.problem(""))
        assertEquals(Problem.EMPTY, NowPattern.problem("   "))
        assertEquals(Problem.NO_FIELD, NowPattern.problem("--:--"))
        assertEquals(Problem.NO_FIELD, NowPattern.problem("'YYYY'"))
        assertEquals(Problem.LETTERS_AS_TEXT, NowPattern.problem("YYYY at HH"))
        assertEquals(Problem.LETTERS_AS_TEXT, NowPattern.problem("YYY-MM"))
        assertEquals(Problem.TWELVE_HOUR_NO_MARK, NowPattern.problem("hh:mm"))
        assertEquals(Problem.OPEN_QUOTE, NowPattern.problem("HH 'o clock"))
        for (preset in NowPreset.entries) preset.pattern?.let { assertNull(it, NowPattern.problem(it)) }
        assertTrue(Problem.EMPTY.blocking && Problem.NO_FIELD.blocking)
        assertFalse(Problem.LETTERS_AS_TEXT.blocking || Problem.TWELVE_HOUR_NO_MARK.blocking || Problem.OPEN_QUOTE.blocking)
        // A warned pattern is still written: the stray letters as they stand.
        assertEquals("2025 at 23", NowPattern.format("YYYY at HH", evening))
    }

    @Test
    fun theStoredChoiceResolves() {
        val date = NowPreset.Kind.DATE
        assertEquals(NowFormat.Phone, date.resolve(null, null))
        assertEquals(NowFormat.Phone, date.resolve("rfc", null))
        // The old ISO value, as a setting or an old backup holds it.
        assertEquals(NowFormat.Pattern("YYYY-MM-DD"), date.resolve("iso", null))
        assertEquals(NowFormat.Pattern("DD/MM"), date.resolve("custom", "DD/MM"))
        // A custom pattern that writes nothing useful falls back to the list's own default.
        assertEquals(NowFormat.Phone, date.resolve("custom", ""))
        assertEquals(NowFormat.Phone, date.resolve("custom", null))
        assertEquals(NowFormat.Pattern("hh:mm"), NowPreset.Kind.TIME.resolve("custom", "hh:mm"))
        assertEquals(NowFormat.Pattern("YYYY-MM-DD HH:mm:ss"), NowPreset.Kind.STAMP.resolve(null, null))
        assertEquals(NowFormat.Pattern("YYYY-MM-DD HH:mm:ss"), NowPreset.Kind.STAMP.resolve("custom", "x"))
        assertEquals(NowFormat.Pattern("YYYYMMDD-HHmmss"), NowPreset.Kind.STAMP.resolve("compact", null))
    }

    @Test
    fun theConfigReadsTheSettings() {
        val plain = MapPrefs.config()
        assertEquals(NowFormat.Phone, plain.dateFormat)
        assertEquals(NowFormat.Phone, plain.timeFormat)
        assertEquals(NowFormat.Pattern("YYYY-MM-DD HH:mm:ss"), plain.stampFormat)
        val set = MapPrefs.config(
            mapOf(
                Prefs.DATE_STYLE to "dotted",
                Prefs.TIME_STYLE to "custom",
                Prefs.TIME_PATTERN to "HH.mm",
                Prefs.STAMP_STYLE to "compact",
            ),
        )
        assertEquals(NowFormat.Pattern("DD.MM.YYYY"), set.dateFormat)
        assertEquals(NowFormat.Pattern("HH.mm"), set.timeFormat)
        assertEquals(NowFormat.Pattern("YYYYMMDD-HHmmss"), set.stampFormat)
    }

    @Test
    fun theListsOfferEveryChoiceAndShipTheCodeDefault() {
        val arrays = res("values/arrays.xml")
        val it = res("values-it/arrays.xml")
        val prefs = res("xml/keyboard_prefs.xml")
        val lists = listOf(
            Triple("date_style", Prefs.DATE_STYLE, NowPreset.Kind.DATE) to Prefs.DEFAULT_DATE_STYLE,
            Triple("time_style", Prefs.TIME_STYLE, NowPreset.Kind.TIME) to Prefs.DEFAULT_TIME_STYLE,
            Triple("stamp_style", Prefs.STAMP_STYLE, NowPreset.Kind.STAMP) to Prefs.DEFAULT_STAMP_STYLE,
        )
        for ((list, codeDefault) in lists) {
            val (name, key, kind) = list
            val values = items(arrays, "${name}_values")
            assertEquals(name, kind.choices.map { it.value }, values)
            assertEquals(name, values.size, items(arrays, "${name}_entries").size)
            assertEquals(name, values.size, items(it, "${name}_entries").size)
            val default = Regex("""android:key="$key"[^>]*?android:defaultValue="([^"]+)"""")
                .find(prefs)?.groupValues?.get(1)
            assertEquals(name, codeDefault, default)
            assertEquals(name, kind.default.value, codeDefault)
        }
        // The old date key kept, so a phone set to ISO stays on ISO with no migration step.
        assertEquals("pref_date_style", Prefs.DATE_STYLE)
        assertEquals("iso", NowPreset.DATE_ISO.value)
    }

    @Test
    fun theActionsReadTheirFormats() {
        // The write itself needs a live editor; the format each action takes is guarded here.
        val src = ImeSources.declaring("private fun insertNow(action: EditorAction)").text
        assertTrue(src.contains("EditorAction.DATE -> ime.config.dateFormat"))
        assertTrue(src.contains("EditorAction.TIME -> ime.config.timeFormat"))
        assertTrue(src.contains("else -> ime.config.stampFormat"))
        assertTrue(EditorAction.STAMP in ActionRow.ALL)
    }

    @Test
    fun theSettingsTravelInABackup() {
        for (key in listOf(
            Prefs.DATE_STYLE, Prefs.DATE_PATTERN, Prefs.TIME_STYLE,
            Prefs.TIME_PATTERN, Prefs.STAMP_STYLE, Prefs.STAMP_PATTERN,
        )) {
            assertTrue(key, Backup.carried(key))
        }
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
