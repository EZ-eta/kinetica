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

package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a new clipboard content must pass to be kept: the switch, the field, the app's mark, text. */
class ClipIntakeTest {

    private fun reason(
        enabled: Boolean = true,
        teachesNothing: Boolean = false,
        sensitive: Boolean = false,
        items: Int = 1,
        text: String? = "via Roma 12",
    ) = ClipIntake.skipReason(enabled, teachesNothing, sensitive, items, text)

    @Test
    fun aPlainCopyIsKept() {
        assertNull(reason())
    }

    @Test
    fun offKeepsNothing() {
        assertEquals("off", reason(enabled = false))
    }

    @Test
    fun aPrivateFieldKeepsNothing() {
        assertEquals("private", reason(teachesNothing = true))
        // Private wins over every other reason: the trace says why it matters.
        assertEquals("private", reason(teachesNothing = true, sensitive = true, text = null))
    }

    @Test
    fun aClipTheAppMarksSensitiveIsNotKept() {
        assertEquals("sensitive", reason(sensitive = true))
    }

    @Test
    fun onlyOnePlainTextIsKept() {
        assertEquals("notext", reason(items = 2))
        assertEquals("notext", reason(text = null))
        assertEquals("notext", reason(items = 0, text = null))
        assertEquals("empty", reason(text = " \n\t"))
    }

    @Test
    fun theSensitiveExtraIsAndroidsName() {
        // ClipDescription.EXTRA_IS_SENSITIVE's value, read by string so phones before 33 honour it.
        assertEquals("android.content.extra.IS_SENSITIVE", ClipIntake.EXTRA_IS_SENSITIVE)
    }
}
