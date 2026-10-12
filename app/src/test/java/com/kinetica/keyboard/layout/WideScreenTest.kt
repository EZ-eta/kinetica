package com.kinetica.keyboard.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An unfolded phone held upright is a landscape-wide screen: the arrangement applies there too,
 * not the full-width board that stretched its keys.
 */
class WideScreenTest {

    @Test
    fun landscapeAlwaysArranges() {
        assertTrue(LandscapeArrangement.appliesTo(landscape = true, widthDp = 400, wideToo = false))
    }

    @Test
    fun anUnfoldedPhoneUprightArrangesAndAPhoneDoesNot() {
        // A Pixel Fold unfolded is about 673 dp wide upright; a Pixel 7 is 411.
        assertTrue(LandscapeArrangement.appliesTo(landscape = false, widthDp = 673, wideToo = true))
        assertFalse(LandscapeArrangement.appliesTo(landscape = false, widthDp = 411, wideToo = true))
        assertTrue(LandscapeArrangement.appliesTo(false, LandscapeArrangement.WIDE_SCREEN_DP, true))
    }

    @Test
    fun theSettingOffKeepsTheFullWidthBoard() {
        assertFalse(LandscapeArrangement.appliesTo(landscape = false, widthDp = 673, wideToo = false))
    }
}
