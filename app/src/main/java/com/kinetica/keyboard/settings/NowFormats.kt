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

import java.time.LocalDateTime

/**
 * The pattern a user types for the date, time and stamp actions.
 *
 * Its own small language, not `DateTimeFormatter`'s: there `YYYY` is the week's year and `DD` the
 * day of the year, so `YYYY-MM-DD` would write a wrong date one week in the year. Here the letters
 * mean what a person writing a date expects. Digits are always Latin, so a stamp sorts and parses
 * anywhere.
 *
 * - `YYYY` year, `YY` its last two digits; `MM` month, `M` without the zero; `DD` day, `D` too.
 * - `HH` hour 00-23, `H`; `hh` hour 01-12, `h`; `mm` minute; `ss` second; `A` AM or PM.
 * - `Y`, `D`, `S` and `A` read in either case; `M` is the month and `m` the minute, `H` and `h` differ.
 * - Text in single quotes is written as it stands; `''` is one quote.
 * - A word with a letter that is no field is written whole, with a warning: `at`, never `PM` + `t`.
 */
object NowPattern {

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Field(val field: NowField) : Part
    }

    enum class NowField { YEAR4, YEAR2, MONTH2, MONTH, DAY2, DAY, HOUR2, HOUR, HOUR12_2, HOUR12, MINUTE2, SECOND2, AM_PM }

    /** What is wrong with a typed pattern, worst first. */
    enum class Problem(val blocking: Boolean) {
        /** Nothing typed. The preset's own format is used instead. */
        EMPTY(true),
        /** No date or time field: the action would write the same text every time. Not used. */
        NO_FIELD(true),
        /** A letter that is no field, written as it stands. Used, with a warning. */
        LETTERS_AS_TEXT(false),
        /** A 12-hour field with no AM or PM: 9 in the morning and 9 at night read alike. Used. */
        TWELVE_HOUR_NO_MARK(false),
        /** A quote never closed: the rest is written as it stands. Used. */
        OPEN_QUOTE(false),
    }

    fun parse(pattern: String): List<Part> = scan(pattern).parts

    /** The worst problem in [pattern], or null when it is a clean pattern. */
    fun problem(pattern: String): Problem? {
        if (pattern.isBlank()) return Problem.EMPTY
        val s = scan(pattern)
        val fields = s.parts.filterIsInstance<Part.Field>().map { it.field }
        return when {
            fields.isEmpty() -> Problem.NO_FIELD
            s.strayLetters -> Problem.LETTERS_AS_TEXT
            fields.any { it == NowField.HOUR12 || it == NowField.HOUR12_2 } && NowField.AM_PM !in fields ->
                Problem.TWELVE_HOUR_NO_MARK
            s.openQuote -> Problem.OPEN_QUOTE
            else -> null
        }
    }

    /** True when [pattern] can be used as typed, warnings aside. */
    fun usable(pattern: String): Boolean = problem(pattern)?.blocking != true

    fun format(pattern: String, at: LocalDateTime): String = format(parse(pattern), at)

    fun format(parts: List<Part>, at: LocalDateTime): String = buildString {
        for (p in parts) {
            when (p) {
                is Part.Text -> append(p.text)
                is Part.Field -> append(value(p.field, at))
            }
        }
    }

    private fun value(f: NowField, at: LocalDateTime): String = when (f) {
        NowField.YEAR4 -> pad(at.year, 4)
        NowField.YEAR2 -> pad(Math.floorMod(at.year, 100), 2)
        NowField.MONTH2 -> pad(at.monthValue, 2)
        NowField.MONTH -> at.monthValue.toString()
        NowField.DAY2 -> pad(at.dayOfMonth, 2)
        NowField.DAY -> at.dayOfMonth.toString()
        NowField.HOUR2 -> pad(at.hour, 2)
        NowField.HOUR -> at.hour.toString()
        NowField.HOUR12_2 -> pad(twelve(at.hour), 2)
        NowField.HOUR12 -> twelve(at.hour).toString()
        NowField.MINUTE2 -> pad(at.minute, 2)
        NowField.SECOND2 -> pad(at.second, 2)
        NowField.AM_PM -> if (at.hour < 12) "AM" else "PM"
    }

    private fun twelve(hour: Int): Int = if (hour % 12 == 0) 12 else hour % 12

    // Built by hand: Int.toString is ASCII whatever the default locale, String.format is not.
    private fun pad(n: Int, width: Int): String = n.toString().padStart(width, '0')

    private class Scan(val parts: List<Part>, val strayLetters: Boolean, val openQuote: Boolean)

    private fun scan(pattern: String): Scan {
        val parts = ArrayList<Part>()
        val text = StringBuilder()
        var stray = false
        var openQuote = false
        fun flush() {
            if (text.isNotEmpty()) parts += Part.Text(text.toString())
            text.clear()
        }
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    text.append('\'')
                    i += 2
                    continue
                }
                // Quoted text up to the closing quote; a doubled quote inside is one quote.
                var j = i + 1
                var closed = false
                while (j < pattern.length) {
                    if (pattern[j] == '\'') {
                        if (j + 1 < pattern.length && pattern[j + 1] == '\'') {
                            text.append('\'')
                            j += 2
                            continue
                        }
                        closed = true
                        break
                    }
                    text.append(pattern[j])
                    j++
                }
                if (!closed) openQuote = true
                i = j + 1
                continue
            }
            if (!c.isLetter()) {
                text.append(c)
                i++
                continue
            }
            // A word of letters is fields only when every run in it is one, as `YYYYMMDD`; one
            // stray letter makes the whole word text, so the `a` of `at` is never AM or PM.
            var wordEnd = i + 1
            while (wordEnd < pattern.length && pattern[wordEnd].isLetter()) wordEnd++
            val fields = ArrayList<NowField>()
            var k = i
            while (k < wordEnd) {
                var end = k + 1
                while (end < wordEnd && pattern[end] == pattern[k]) end++
                val field = fieldOf(pattern[k], end - k)
                if (field == null) {
                    fields.clear()
                    break
                }
                fields += field
                k = end
            }
            if (fields.isEmpty()) {
                stray = true
                text.append(pattern, i, wordEnd)
            } else {
                flush()
                fields.forEach { parts += Part.Field(it) }
            }
            i = wordEnd
        }
        flush()
        return Scan(parts, stray, openQuote)
    }

    private fun fieldOf(c: Char, run: Int): NowField? = when (c) {
        'Y', 'y' -> when (run) { 4 -> NowField.YEAR4; 2 -> NowField.YEAR2; else -> null }
        'M' -> when (run) { 2 -> NowField.MONTH2; 1 -> NowField.MONTH; else -> null }
        'D', 'd' -> when (run) { 2 -> NowField.DAY2; 1 -> NowField.DAY; else -> null }
        'H' -> when (run) { 2 -> NowField.HOUR2; 1 -> NowField.HOUR; else -> null }
        'h' -> when (run) { 2 -> NowField.HOUR12_2; 1 -> NowField.HOUR12; else -> null }
        'm' -> if (run == 2) NowField.MINUTE2 else null
        'S', 's' -> if (run == 2) NowField.SECOND2 else null
        'A', 'a' -> if (run == 1) NowField.AM_PM else null
        else -> null
    }
}

/** How one of the three actions writes the moment: the phone's own format, or a pattern. */
sealed interface NowFormat {
    object Phone : NowFormat
    data class Pattern(val pattern: String) : NowFormat {
        val parts: List<NowPattern.Part> = NowPattern.parse(pattern)
        fun format(at: LocalDateTime): String = NowPattern.format(parts, at)
    }
}

/**
 * A choice in one of the three lists: its stored value and its pattern. [PHONE] has none, the
 * phone's format is read at the time; [CUSTOM] takes the typed one.
 */
enum class NowPreset(val value: String, val pattern: String?) {
    PHONE("system", null),
    CUSTOM("custom", null),

    // The value is the old date setting's, kept so a setting and a backup from before still read as ISO.
    DATE_ISO("iso", "YYYY-MM-DD"),
    DATE_SPACED("spaced", "YYYY MM DD"),
    DATE_DOTTED("dotted", "DD.MM.YYYY"),
    DATE_COMPACT("compact", "YYYYMMDD"),

    TIME_HM("hm", "HH:mm"),
    TIME_HMS("hms", "HH:mm:ss"),
    TIME_COMPACT("hms_compact", "HHmmss"),

    STAMP_ISO("iso", "YYYY-MM-DD HH:mm:ss"),
    STAMP_COMPACT("compact", "YYYYMMDD-HHmmss"),
    ;

    /** Which action a list is for, its choices in order and the one it ships with. */
    enum class Kind(val choices: List<NowPreset>, val default: NowPreset) {
        DATE(listOf(PHONE, DATE_ISO, DATE_SPACED, DATE_DOTTED, DATE_COMPACT, CUSTOM), PHONE),
        TIME(listOf(PHONE, TIME_HM, TIME_HMS, TIME_COMPACT, CUSTOM), PHONE),
        STAMP(listOf(STAMP_ISO, STAMP_COMPACT, CUSTOM), STAMP_ISO),
        ;

        fun preset(value: String?): NowPreset = choices.firstOrNull { it.value == value } ?: default

        /**
         * The format a stored choice and typed pattern stand for. A custom pattern that cannot
         * be used, empty or with no field, falls back to the list's default, never to nothing.
         */
        fun resolve(value: String?, typed: String?): NowFormat {
            val p = preset(value)
            val pattern = when {
                p == CUSTOM && typed != null && NowPattern.usable(typed) -> typed
                p == CUSTOM -> default.pattern
                else -> p.pattern
            }
            return if (pattern == null) NowFormat.Phone else NowFormat.Pattern(pattern)
        }
    }
}
