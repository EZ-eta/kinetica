package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.Alphabet
import kotlin.math.abs

/**
 * The symbol pages' three upper rows as plain text, one line per row, keys apart by spaces. The
 * bottom row, the page key and backspace stay where the letters' are, so a reach learned on the
 * letters still lands.
 */
object SymbolRows {

    const val ROWS = 3

    /** Keys a row holds: two full rows, and the third between the page key and backspace. */
    val MAX_KEYS = intArrayOf(10, 10, 7)

    /** Longest key, in characters as seen: `:-)` fits, a sentence does not. */
    const val MAX_KEY_CHARS = 4

    private const val KEY_W = 0.10f
    private val ROW_Y = floatArrayOf(0f, 0.25f, 0.5f)
    private val SPAN_LEFT = floatArrayOf(0f, 0f, 0.15f)
    private val SPAN_RIGHT = floatArrayOf(1f, 1f, 0.85f)

    enum class Problem { TOO_MANY, LETTER, TOO_LONG, TOO_MANY_ROWS }

    /** What is wrong with a row, and the key that is, when one key is. */
    data class Found(val problem: Problem, val key: String?)

    fun parse(line: String?): List<String> =
        line?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * The first problem of [line] as row [row], or null for a row that can be used. Blank is no
     * problem: it is the built-in row. A letter a-z is refused because a page with one turns on
     * swipe decoding over keys that are not a board; `π` or `ß` are no letter of it and stay legal.
     */
    fun problem(line: String?, row: Int): Found? {
        val keys = parse(line)
        for (k in keys) {
            if (k.any { Alphabet.LATIN.isLetter(it.lowercaseChar()) }) return Found(Problem.LETTER, k)
            if (k.codePointCount(0, k.length) > MAX_KEY_CHARS) return Found(Problem.TOO_LONG, k)
        }
        if (keys.size > MAX_KEYS[row]) return Found(Problem.TOO_MANY, null)
        return null
    }

    /** An editor's text as rows, blank lines at the end dropped. */
    fun lines(text: String): List<String> = text.split('\n').dropLastWhile { it.isBlank() }

    /** The first problem over all rows, with the row it is on; a fourth row is the last one's. */
    fun firstProblem(lines: List<String?>): Pair<Int, Found>? {
        if (lines.size > ROWS) return ROWS to Found(Problem.TOO_MANY_ROWS, null)
        for (r in 0 until ROWS) {
            val found = problem(lines.getOrNull(r), r) ?: continue
            return r to found
        }
        return null
    }

    /** [base]'s own rows as text, for the editor and to tell an edit from the built-in rows. */
    fun defaults(base: KeyboardLayout): List<String> =
        (0 until ROWS).map { r -> rowKeys(base, r).joinToString(" ") { it.output } }

    /**
     * [base] with each row of [lines] that is set and usable in place of its own; a blank row,
     * or one with a problem, keeps the built-in keys. Keys are a letter key wide and centred, so a
     * shorter row keeps its keys' size. A symbol the built-in page also has keeps its hint and
     * long-press list from wherever it was; it keeps its id only in its own row, so no id is held
     * twice, and a new one gets an id of its own.
     */
    fun apply(base: KeyboardLayout, page: Int, lines: List<String?>): KeyboardLayout {
        var keys = base.keys
        for (r in 0 until ROWS) {
            val line = lines.getOrNull(r)
            val typed = parse(line)
            if (typed.isEmpty() || problem(line, r) != null) continue
            val old = rowKeys(base, r)
            val h = old.firstOrNull()?.h ?: KEY_W
            val start = SPAN_LEFT[r] + (SPAN_RIGHT[r] - SPAN_LEFT[r] - typed.size * KEY_W) / 2f
            val taken = HashSet<String>()
            val row = typed.mapIndexed { i, out ->
                val same = old.firstOrNull { it.output == out && it.id !in taken }
                if (same != null) taken.add(same.id)
                val donor = same ?: base.keys.firstOrNull { it.type == KeyType.CHAR && it.output == out }
                Key(
                    id = same?.id ?: "sym${page}_${r + 1}_${i + 1}",
                    type = KeyType.CHAR,
                    label = out,
                    output = out,
                    x = start + i * KEY_W,
                    y = ROW_Y[r],
                    w = KEY_W,
                    h = h,
                    hint = donor?.hint,
                    alternates = donor?.alternates.orEmpty(),
                    alphabet = base.keys.firstOrNull()?.alphabet ?: Alphabet.LATIN,
                )
            }
            val gone = old.toSet()
            keys = keys.filter { it !in gone } + row
        }
        return base.copy(keys = keys)
    }

    /** The symbol pages' names, as their layouts are bundled. */
    val PAGES = setOf("symbols", "symbols2")

    /**
     * [page] with the user's long-press [lists] over the keys of its three upper rows: a symbol
     * with a list takes it in place of its built-in one, the hint dropped so the list's first entry
     * is the plain long press, as a letter's list does. The bottom row keeps its own lists.
     */
    fun withLists(page: KeyboardLayout, lists: Map<String, List<String>>): KeyboardLayout {
        if (lists.isEmpty()) return page
        var changed = false
        val keys = page.keys.map { k ->
            val list = if (inUpperRows(k)) lists[k.output] else null
            if (list.isNullOrEmpty() || (list == k.alternates && k.hint == null)) {
                k
            } else {
                changed = true
                k.copy(alternates = list, hint = null)
            }
        }
        return if (changed) page.copy(keys = keys) else page
    }

    /** Every symbol of [page]'s three upper rows, row by row and left to right, each once. */
    fun symbolsOf(page: KeyboardLayout): List<String> =
        (0 until ROWS).flatMap { r -> rowKeys(page, r).map { it.output } }.distinct()

    /** Whether [key] on the page named [pageName] is a symbol a list can be written for. */
    fun carriesList(pageName: String?, key: Key): Boolean = pageName in PAGES && inUpperRows(key)

    private fun inUpperRows(k: Key): Boolean = k.type == KeyType.CHAR && ROW_Y.any { abs(k.y - it) < 1e-3f }

    /** The character keys of row [row], left to right; the page key and backspace are not. */
    private fun rowKeys(base: KeyboardLayout, row: Int): List<Key> =
        base.keys.filter { it.type == KeyType.CHAR && abs(it.y - ROW_Y[row]) < 1e-3f }.sortedBy { it.x }

    private val WHITESPACE = Regex("\\s+")
}
