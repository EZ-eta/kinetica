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

import java.text.Collator
import java.util.Locale

/**
 * The Dictionary screen's order, pure so it can be tested: languages by their name as the phone's
 * language sorts it, so `Čeština` sits with the Cs and not after `Polski`.
 */
object DictionaryRows {

    fun <T> byName(rows: List<T>, label: (T) -> String, locale: Locale): List<T> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return rows.sortedWith { a, b -> collator.compare(label(a), label(b)) }
    }
}
