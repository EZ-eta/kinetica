package com.kinetica.keyboard.settings

/**
 * The two look groups of Theme and look, each a header row over a category of rows, as on Size and
 * layout ([SizeGroups]). The look the keyboard shows now starts open, the other closed; the night
 * group is there only while the day and night looks are on.
 */
object LookGroups {

    const val DAY_HEADER = "pref_look_day"
    const val DAY_ROWS = "pref_look_day_rows"
    const val NIGHT_HEADER = "pref_look_night"
    const val NIGHT_ROWS = "pref_look_night_rows"

    /** Header key to the key of the rows it opens and closes. */
    val GROUPS: Map<String, String> = mapOf(DAY_HEADER to DAY_ROWS, NIGHT_HEADER to NIGHT_ROWS)

    /** Whether [rows] starts open, [nightShown] when the keyboard draws the night look now. */
    fun startsOpen(rows: String, nightShown: Boolean): Boolean = (rows == NIGHT_ROWS) == nightShown
}
