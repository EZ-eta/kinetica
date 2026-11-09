package com.kinetica.keyboard.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The look the keyboard shows now opens its group on Theme and look, the other stays closed. */
class LookGroupsTest {

    @Test
    fun theLookShownNowStartsOpen() {
        assertTrue(LookGroups.startsOpen(LookGroups.DAY_ROWS, nightShown = false))
        assertFalse(LookGroups.startsOpen(LookGroups.NIGHT_ROWS, nightShown = false))
        assertTrue(LookGroups.startsOpen(LookGroups.NIGHT_ROWS, nightShown = true))
        assertFalse(LookGroups.startsOpen(LookGroups.DAY_ROWS, nightShown = true))
    }
}
