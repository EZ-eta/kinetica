package com.kinetica.keyboard.settings

/**
 * The arithmetic behind [SteppedSliderPreference], kept free of Android so it can be tested.
 */
object SliderSteps {

    /**
     * The index of the step nearest [value], the lower one on a tie. A stored value that is
     * not a step, from the old linear slider or a derived default, shows where it is closest
     * instead of jumping to an end.
     */
    fun nearestIndex(steps: IntArray, value: Int): Int {
        require(steps.isNotEmpty()) { "no steps" }
        var best = 0
        for (i in 1 until steps.size) {
            if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) best = i
        }
        return best
    }
}
