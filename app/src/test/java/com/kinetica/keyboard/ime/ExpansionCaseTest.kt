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

/**
 * An expansion trigger's case. Matched exactly, `andor` misses at a sentence start where the
 * shift writes `Andor`, and every expansion has to be stored twice (#24).
 */
class ExpansionCaseTest {

    private val table = ExpansionTable(
        mapOf(
            "andor" to listOf("and/or"),
            "eg" to listOf("e.g."),
            "degc" to listOf("°C"),
            "^^" to listOf("^_^"),
            "Nasa" to listOf("the NASA site"),
            "nasa" to listOf("nasa"),
        ),
    )

    @Test
    fun exactKeepsTheOldBehaviour() {
        assertEquals("andor", table.storedFor("andor", TriggerCase.EXACT))
        assertNull(table.storedFor("Andor", TriggerCase.EXACT))
    }

    @Test
    fun theCaseModesMatchWhateverTheShiftWrote() {
        for (mode in listOf(TriggerCase.IGNORE, TriggerCase.CARRY)) {
            assertEquals("andor", table.storedFor("Andor", mode))
            assertEquals("andor", table.storedFor("ANDOR", mode))
        }
    }

    @Test
    fun aRowWrittenForTheCapitalKeepsItsOwnTarget() {
        // An exact row wins over the lowercase index, and the index prefers the lowercase row.
        assertEquals("Nasa", table.storedFor("Nasa", TriggerCase.CARRY))
        assertEquals("nasa", table.storedFor("NASA", TriggerCase.CARRY))
    }

    @Test
    fun carryingTheCaseCapitalizesOnlyWhatTheTriggerDid() {
        val carry = TriggerCase.CARRY
        assertEquals("And/or", carryCase("Andor", "andor", "and/or", carry))
        assertEquals("AND/OR", carryCase("ANDOR", "andor", "and/or", carry))
        assertEquals("E.g.", carryCase("Eg", "eg", "e.g.", carry))
        // The first letter only: a name or an acronym inside the target is left alone.
        assertEquals("The NASA site", carryCase("Nasa", "nasa", "the NASA site", carry))
        // Never lowercased: `degc` gives `°C` however it was typed.
        assertEquals("°C", carryCase("degc", "degc", "°C", carry))
        assertEquals("°C", carryCase("Degc", "degc", "°C", carry))
        // No letters in the trigger, no case to carry.
        assertEquals("^_^", carryCase("^^", "^^", "^_^", carry))
        // One letter is a capital, not shouting.
        assertEquals("Before", carryCase("B4", "b4", "before", carry))
        assertEquals("With", carryCase("W/", "w/", "with", carry))
    }

    @Test
    fun ignoringTheCaseLeavesTheTargetAsStored() {
        assertEquals("and/or", carryCase("Andor", "andor", "and/or", TriggerCase.IGNORE))
        assertEquals("and/or", carryCase("Andor", "andor", "and/or", TriggerCase.EXACT))
    }
}
