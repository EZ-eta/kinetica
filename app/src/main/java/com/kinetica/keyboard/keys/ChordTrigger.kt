package com.kinetica.keyboard.keys

/**
 * The key held to make the next tap a chord: `?123`, or a symbol page's page key, and the
 * spacebar, which sits in the middle of every page. Chosen per chord, so `?123`+`l` and
 * space+`l` are two chords.
 */
enum class ChordTrigger { MODE, SPACE }

/**
 * A chord's identity: the held key and the tapped one.
 *
 * Stored as the tapped key alone for `?123`, which is every chord an earlier build wrote, and as
 * `space:<key>` for the spacebar. An earlier build reads only one-character keys, so a newer row
 * is no chord there instead of a wrong one, and the table needs no migration.
 */
data class ChordKey(val trigger: ChordTrigger, val key: Char) {

    fun encode(): String = when (trigger) {
        ChordTrigger.MODE -> key.toString()
        ChordTrigger.SPACE -> SPACE_PREFIX + key
    }

    companion object {
        const val SPACE_PREFIX = "space:"

        /** The chord a stored key names, or null for one this build cannot read. */
        fun decode(stored: String): ChordKey? = when {
            stored.length == 1 -> ChordKey(ChordTrigger.MODE, stored[0].lowercaseChar())
            stored.length == SPACE_PREFIX.length + 1 && stored.startsWith(SPACE_PREFIX) ->
                ChordKey(ChordTrigger.SPACE, stored.last().lowercaseChar())
            else -> null
        }
    }
}
