package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spacebar slide's travel-to-steps arithmetic, and the two settings over it.
 *
 * Pure because the controller is: it takes x positions and emits steps, and knows nothing
 * about the editor. Density is 1 throughout so dp and px coincide and the numbers in each
 * test are the dp the finger travelled.
 */
class SpacebarCursorControllerTest {

    private class Recorder {
        val steps = ArrayList<Pair<Int, Boolean>>()
        val controller = SpacebarCursorController(1f) { dir, byWord -> steps.add(dir to byWord) }
        val directions: List<Int> get() = steps.map { it.first }
    }

    @Test
    fun aShortTouchIsStillASpace() {
        val r = Recorder()
        r.controller.onDown(100f)
        r.controller.onMove(104f)
        assertEquals(
            "under the enter threshold it stays a tap",
            SpacebarCursorController.Lift.SPACE,
            r.controller.onUp(),
        )
        assertEquals(emptyList<Int>(), r.directions)
    }

    @Test
    fun theDefaultStepIsUnchanged() {
        // 20dp per step from a moving anchor set where cursor mode armed, i.e. at +8.
        val r = Recorder()
        r.controller.onDown(0f)
        r.controller.onMove(8f)
        r.controller.onMove(48f)
        assertEquals(
            "cursor mode is not a tap",
            SpacebarCursorController.Lift.SLIDE,
            r.controller.onUp(),
        )
        assertEquals(listOf(1, 1), r.directions)
    }

    @Test
    fun aShorterStepMovesFurtherForTheSameTravel() {
        // The request: "make the scroll sensitivity adjustable so it moves faster".
        val r = Recorder()
        r.controller.stepDp = 10f
        r.controller.onDown(0f)
        r.controller.onMove(8f)
        r.controller.onMove(48f)
        assertEquals(listOf(1, 1, 1, 1), r.directions)
    }

    @Test
    fun aLongerStepMovesLess() {
        val r = Recorder()
        r.controller.stepDp = 40f
        r.controller.onDown(0f)
        r.controller.onMove(8f)
        r.controller.onMove(48f)
        assertEquals(listOf(1), r.directions)
    }

    @Test
    fun theStepCannotUndercutTheThresholdThatArmsCursorMode() {
        // A step shorter than the 8dp that enters cursor mode would fire on the very
        // sample that armed it, so the first movement would jump two.
        val r = Recorder()
        r.controller.stepDp = 1f
        assertEquals(SpacebarCursorController.ENTER_SLIDE_DP, r.controller.effectiveStepDp())
        r.controller.stepDp = 500f
        assertEquals(SpacebarCursorController.MAX_STEP_DP, r.controller.effectiveStepDp())
    }

    @Test
    fun slidingBackTheOtherWayReverses() {
        val r = Recorder()
        r.controller.onDown(100f)
        r.controller.onMove(92f)
        r.controller.onMove(52f)
        assertEquals(listOf(-1, -1), r.directions)
        r.controller.onMove(92f)
        assertEquals(listOf(-1, -1, 1, 1), r.directions)
    }

    @Test
    fun theGranularityIsCarriedOutToTheListener() {
        // The controller does not know what a word is; the service does. Passing the flag
        // out is what keeps the arithmetic here indifferent to it.
        val r = Recorder()
        r.controller.wordMode = true
        r.controller.onDown(0f)
        r.controller.onMove(8f)
        r.controller.onMove(28f)
        assertEquals(listOf(1 to true), r.steps)
    }

    @Test
    fun charactersRemainTheDefault() {
        val r = Recorder()
        r.controller.onDown(0f)
        r.controller.onMove(8f)
        r.controller.onMove(28f)
        assertEquals(listOf(1 to false), r.steps)
    }

    // R35, the spaceless space: the left 30% of the key ends the word and writes no
    // space. The key here is 100 wide from x=0, so the zone is x < 30.

    @Test
    fun theZoneIsInertUntilItIsTurnedOn() {
        val r = Recorder()
        r.controller.onDown(10f, 0f, 100f)
        assertEquals(SpacebarCursorController.Lift.SPACE, r.controller.onUp())
    }

    @Test
    fun aTapInTheLeftThirdIsASpacelessSpace() {
        val r = Recorder()
        r.controller.spacelessZone = true
        r.controller.onDown(10f, 0f, 100f)
        assertEquals(SpacebarCursorController.Lift.SPACELESS, r.controller.onUp())
    }

    @Test
    fun aTapPastTheZoneIsAnOrdinarySpace() {
        val r = Recorder()
        r.controller.spacelessZone = true
        r.controller.onDown(50f, 0f, 100f)
        assertEquals(SpacebarCursorController.Lift.SPACE, r.controller.onUp())
    }

    @Test
    fun theSlideWinsOverTheZoneItStartedIn() {
        // The finger made a slide, so it is a slide. Nothing may end a word behind a
        // gesture whose whole purpose was to move the cursor.
        val r = Recorder()
        r.controller.spacelessZone = true
        r.controller.onDown(10f, 0f, 100f)
        r.controller.onMove(18f)
        r.controller.onMove(58f)
        assertEquals(SpacebarCursorController.Lift.SLIDE, r.controller.onUp())
        assertEquals(listOf(1, 1), r.directions)
    }

    @Test
    fun aCallerThatDoesNotKnowTheKeyRectNeverArmsTheZone() {
        // The default arguments, i.e. every caller written before the zone existed.
        val r = Recorder()
        r.controller.spacelessZone = true
        r.controller.onDown(10f)
        assertEquals(SpacebarCursorController.Lift.SPACE, r.controller.onUp())
    }
}
