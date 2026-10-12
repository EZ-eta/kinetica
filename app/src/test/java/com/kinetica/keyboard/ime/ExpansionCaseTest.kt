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
