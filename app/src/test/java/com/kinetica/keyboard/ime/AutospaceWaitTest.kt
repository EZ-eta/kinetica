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
import org.junit.Test

/**
 * The autospace delay is counted from the thumb's lift.
 *
 * Counted from the moment the decode lands, a 10 ms setting would wait 10 ms plus the decode,
 * tens of milliseconds more, and the slider's low end would say less than it does.
 */
class AutospaceWaitTest {

    @Test
    fun theDecodeTimeComesOffTheWait() {
        assertEquals(260L, autospaceWait(delay = 300L, liftAt = 1_000L, now = 1_040L))
    }

    @Test
    fun aDecodeSlowerThanTheDelaySpacesAtOnce() {
        assertEquals(0L, autospaceWait(delay = 10L, liftAt = 1_000L, now = 1_069L))
    }

    @Test
    fun anUnknownOrFutureLiftWaitsTheWholeDelay() {
        assertEquals(300L, autospaceWait(delay = 300L, liftAt = -1L, now = 5L))
        assertEquals(300L, autospaceWait(delay = 300L, liftAt = 2_000L, now = 1_000L))
    }
}
