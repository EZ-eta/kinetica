package com.kinetica.keyboard.ui

/**
 * What a bar word is to the dictionaries, drawn beside it when the marks are on: a bundled word has
 * none, a learned or added one `C`, an unknown one `+` when its commit learns it, else `?`.
 */
enum class BarMark(val glyph: String?) {
    NONE(null),
    LEARN("+"),
    UNKNOWN("?"),
    PERSONAL("C"),
}
