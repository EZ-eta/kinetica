package com.kinetica.keyboard.settings

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the date action writes today's date: as the phone is set, or ISO 8601 (`2025-12-31`). */
enum class DateStyle(val value: String) {
    SYSTEM("system"),
    ISO("iso"),
    ;

    companion object {
        fun fromPref(value: String?): DateStyle = if (value == ISO.value) ISO else SYSTEM

        /** Latin digits whatever the phone's language, so the date sorts and parses anywhere. */
        fun iso(date: LocalDate): String = ISO_DATE.format(date)

        private val ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
    }
}
