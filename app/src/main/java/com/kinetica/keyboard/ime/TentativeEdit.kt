package com.kinetica.keyboard.ime

/**
 * The smallest rewrite of the word in hand: the letters it shares in front are left alone.
 *
 * A merge that adds the other thumb's letter used to delete the whole word and write it again,
 * 10 ms after the swipe's own write. A WebView rich editor (ProseMirror) answers a delete with
 * a 50 ms check and, finding no change it could read, deletes one more character: `the` became
 * `th` 8 times in 8. Growing the word is now a plain insert, with no delete to answer.
 */
internal object TentativeEdit {

    /** Delete [drop] characters before the cursor, then write [add]. */
    data class Edit(val drop: Int, val add: String) {
        val writesNothing: Boolean get() = drop == 0 && add.isEmpty()
    }

    /** The rewrite of [old], just before the cursor, into [new]. */
    fun between(old: String, new: String): Edit {
        var keep = 0
        val most = minOf(old.length, new.length)
        while (keep < most && old[keep] == new[keep]) keep++
        // Never cut between the halves of an emoji: the editor would hold a lone surrogate.
        if (keep > 0 && Character.isHighSurrogate(old[keep - 1])) keep--
        return Edit(old.length - keep, new.substring(keep))
    }
}
