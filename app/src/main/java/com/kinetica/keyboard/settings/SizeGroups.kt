package com.kinetica.keyboard.settings

/**
 * The two orientation groups of Size and layout, each a header row over a category of rows. The
 * group of the orientation the settings are held in starts open, the other closed, so landscape
 * stays out of the way upright and its own sizes come first sideways.
 */
object SizeGroups {

    const val PORTRAIT_HEADER = "pref_size_portrait"
    const val PORTRAIT_ROWS = "pref_size_portrait_rows"
    const val LANDSCAPE_HEADER = "pref_size_landscape"
    const val LANDSCAPE_ROWS = "pref_size_landscape_rows"

    /** Header key to the key of the rows it opens and closes. */
    val GROUPS: Map<String, String> = mapOf(PORTRAIT_HEADER to PORTRAIT_ROWS, LANDSCAPE_HEADER to LANDSCAPE_ROWS)

    /** Whether [rows] starts open with the settings held [landscape]. */
    fun startsOpen(rows: String, landscape: Boolean): Boolean = (rows == LANDSCAPE_ROWS) == landscape
}
