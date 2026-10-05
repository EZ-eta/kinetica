package com.kinetica.keyboard.ui

/**
 * Ordering for the emoji picker's "frequently used" tab.
 *
 * Pure, because the picker is a view with no JVM reach; same shape as
 * PersonalWordRows.sortedForDisplay.
 *
 * Ordered by count, so one emoji sent once cannot push out the one sent every day. Recency
 * breaks ties and the spelling breaks those, so the order is deterministic for a given store.
 */
object EmojiRecents {

    /**
     * Three rows of EmojiPickerView.COLUMNS: enough to be worth opening, few enough that the
     * panel never scrolls.
     */
    const val MAX = 24

    /** One emoji's usage, free of Room and of Android. */
    data class Use(val emoji: String, val count: Int, val updatedAt: Long)

    /**
     * The tab's contents, best first. Zero and negative counts are dropped: a count fallen to
     * zero means the same as one never recorded.
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
