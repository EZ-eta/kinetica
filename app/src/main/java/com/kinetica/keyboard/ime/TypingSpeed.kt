package com.kinetica.keyboard.ime

/**
 * Words per minute over the last few seconds of typing, for the spacebar readout.
 *
 * A burst meter, not a session average: one record per committed word, a pause empties the
 * history, records past a short window drop off, and the number eases toward each new reading.
 * The rate is the standard one, characters over five per minute, with one space after every
 * word. It moves at each commit, not while a word is being drawn.
 */
class TypingSpeed(
    private val idleBreakMs: Long = IDLE_BREAK_MS,
    private val spanMs: Long = SPAN_MS,
) {
    private class Record(val startMs: Long, val endMs: Long, val chars: Int)

    private val records = ArrayDeque<Record>()

    /** The number the spacebar shows, or null while there is nothing to show. */
    var display: Float? = null
        private set

    /**
     * A committed word of [letters] letters whose first touch was at [startMs] and last at
     * [endMs], both uptime milliseconds. Returns the new [display].
     */
    fun logWord(startMs: Long, endMs: Long, letters: Int): Float? {
        val last = records.lastOrNull()
        if (last != null && startMs - last.endMs > idleBreakMs) clear()
        records.addLast(Record(startMs, maxOf(startMs, endMs), letters + 1))
        while (records.size > 1 && endMs - records.first().startMs > spanMs) records.removeFirst()
        val reading = rate() ?: return display
        val s = display
        display = if (s == null) reading else s + EASING * (reading - s)
        return display
    }

    /** The unsmoothed rate over the window, or null under [WORDS_NEEDED] words. */
    fun rate(): Float? {
        if (records.size < WORDS_NEEDED) return null
        val elapsed = records.last().endMs - records.first().startMs
        if (elapsed <= 0) return null
        val chars = records.sumOf { it.chars }
        return chars / CHARS_PER_WORD / (elapsed / 60_000f)
    }

    fun clear() {
        records.clear()
        display = null
    }

    companion object {
        /**
         * A gap past this is a pause, not typing: the p75 of the gaps between swiped commits
         * over 376 captured commits (median 1.9 s, p75 4.0 s).
         */
        const val IDLE_BREAK_MS = 4_000L

        /**
         * About six words at that pace (0.5 s a word, 1.9 s between): enough to average one slow
         * word out, short enough to read as the speed of the moment.
         */
        const val SPAN_MS = 15_000L

        /** One word has no span worth dividing by; two do. */
        const val WORDS_NEEDED = 2

        /** Half way to each new reading, so one slow word does not halve the number. */
        const val EASING = 0.5f

        /** The standard word: five characters, the space included. */
        const val CHARS_PER_WORD = 5f
    }
}
