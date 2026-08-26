package com.kinetica.keyboard.ui

/**
 * Ordering for the emoji picker's "frequently used" tab.
 *
 * Pure on purpose: the picker itself is a view with no JVM reach, so the part
 * that decides what the panel shows lives here where it can be tested, in the
 * same shape as PersonalWordRows.sortedForDisplay.
 *
 * Ordered by count rather than by recency. Recency is what a "recent" panel
 * would use, and it is the wrong measure here: one emoji sent once would push
 * out the one sent every day. Recency only breaks ties, so the more recent of
 * two equally used emoji sits first, and the spelling breaks that in turn so
 * the order is deterministic for a given store.
 */
object EmojiRecents {

    /**
     * Three rows of EmojiPickerView.COLUMNS. Enough that the panel is worth
     * opening and small enough that it never scrolls, which is the point: the
     * emoji you use should be reachable without moving.
     */
    const val MAX = 24

    /** One emoji's usage, free of Room and of Android. */
    data class Use(val emoji: String, val count: Int, val updatedAt: Long)

    /**
     * The tab's contents, best first. Zero and negative counts are dropped
     * rather than shown greyed: a count that has fallen to zero is indistinguishable
     * from one that was never recorded.
     */
    fun ordered(uses: Collection<Use>, limit: Int = MAX): List<String> =
        uses.asSequence()
            .filter { it.count > 0 && it.emoji.isNotEmpty() }
            .sortedWith(
                compareByDescending<Use> { it.count }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.emoji },
            )
            .map { it.emoji }
            .distinct()
            .take(limit)
            .toList()
}
