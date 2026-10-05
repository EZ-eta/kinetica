package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.keys.EditorAction

/** Optional, settings-driven edits applied to a loaded layout. */
object LayoutMutations {

    /** Reserved alternate string: committing it opens the emoji picker. */
    const val EMOJI_ALTERNATE = "☺"

    /**
     * Enter's hold cell for a newline, added where enter runs the app's action. Not `⏎`,
     * which is the ENTER action's glyph in the letter lists.
     */
    const val NEWLINE_ALTERNATE = "↵"

    /**
     * Reserved key outputs for editor actions, defined once in [EditorAction] so the comma key
     * and the chord shortcuts agree on what each means. The IME intercepts them before the
     * commit path, as it does [EMOJI_ALTERNATE].
     */
    val ACTION_PASTE = EditorAction.PASTE.output
    val ACTION_SELECT_ALL = EditorAction.SELECT_ALL.output

    /**
     * Enter's alternate-popup cells. No layout JSON gives enter alternates, so these are
     * injected for every alpha layout; the popup shows them alone with the first pre-selected,
     * matching enter's default up-swipe output "?".
     */
    val ENTER_ALTERNATES = listOf("?", "!", ",")

    /**
     * Shift's alternate-popup cells: the case to put the word in hand into.
     *
     * Labels, not outputs: the IME discriminates on `KeyType.SHIFT` and never lets these
     * strings reach the editor, so they need no reserved prefix. The order is [WordCase]'s.
     */
    val SHIFT_CASE_CELLS = listOf("abc", "Abc", "ABC")

    /** Id of the optional apostrophe key. */
    const val APOSTROPHE_KEY_ID = "apostrophe"

    /**
     * Home-row nudge applied with the apostrophe key: the home row (a..l, y=0.25) shifts left by
     * this fraction of the keyboard width, so the apostrophe at the right edge sits apart from
     * "L". 0.015 is about 0.15 kw, far inside the DTW radii, and applies only while the key is on.
     */
    const val APOSTROPHE_HOME_ROW_SHIFT = 0.015f

    /**
     * Optional apostrophe key: a narrow, chromeless (Nintype-style) CHAR key at the right of the
     * home row, so "'" is one tap away for elided and contracted words ("nell'immagine",
     * "don't"). Not a letter key, so the swipe engine never sees it. The home row nudges left by
     * [APOSTROPHE_HOME_ROW_SHIFT]. Alpha-layout chain only; idempotent, because the appended key
     * guards re-entry and the nudge is never doubled.
     */
    fun withApostropheKey(layout: KeyboardLayout): KeyboardLayout {
        if (layout.keys.any { it.id == APOSTROPHE_KEY_ID }) return layout
        // The slot it takes is QWERTY's; a board in another script fills its rows.
        if (layout.alphabet != Alphabet.LATIN) return layout
        val shifted = layout.keys.map { k ->
            if (kotlin.math.abs(k.y - 0.25f) < 0.01f) {
                k.copy(x = k.x - APOSTROPHE_HOME_ROW_SHIFT)
            } else {
                k
            }
        }
        val apos = Key(
            id = APOSTROPHE_KEY_ID, type = KeyType.CHAR, label = "'", output = "'",
            x = 0.95f, y = 0.25f, w = 0.05f, h = 0.25f, chromeless = true,
        )
        return layout.copy(keys = shifted + apos)
    }

    /**
     * Spreads the middle letter row (y=0.25) toward the edges by [amount], 0 to 1. At 1
     * the row fills the width, up to the apostrophe key when that is on; neighbours keep
     * touching. Key centres map about the row's midpoint, so every key keeps its split half
     * (QWERTY's `g` stays centred on 0.5). The key-width unit is the narrowest letter key, a
     * top-row key, so only the home row's own distances grow. On the captured corpus a hand used
     * to its board decodes the same at 0 and 1, and a hand aiming at a flush row reads 252 of
     * 269 indented against 272 of 280.
     */
    fun withHomeRowSpread(layout: KeyboardLayout, amount: Float): KeyboardLayout {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0f) return layout
        val row = layout.keys.filter { it.isLetter && kotlin.math.abs(it.y - 0.25f) < 0.01f }
        if (row.size < 2) return layout
        val left = row.minOf { it.x }
        val right = row.maxOf { it.x + it.w }
        val limit = layout.keys.firstOrNull { it.id == APOSTROPHE_KEY_ID }?.x ?: 1f
        if (left <= SPREAD_SLACK && right >= limit - SPREAD_SLACK) return layout
        val newLeft = left * (1f - a)
        val newRight = right + (limit - right) * a
        val s = (newRight - newLeft) / (right - left)
        val mid = (left + right) / 2f
        val newMid = (newLeft + newRight) / 2f
        val ids = row.mapTo(HashSet()) { it.id }
        return layout.copy(
            keys = layout.keys.map { k ->
                if (k.id !in ids) return@map k
                val w = k.w * s
                val centre = newMid + (k.x + k.w / 2f - mid) * s
                k.copy(x = centre - w / 2f, w = w)
            },
        )
    }

    /** Below this a row already reaches the edge: nothing to spread into. */
    private const val SPREAD_SLACK = 0.001f

    /**
     * Gives the enter key its [ENTER_ALTERNATES] popup cells. Always applied in the alpha-layout
     * chain and only there, so the numpad layer's enter (slide-left back to letters) is
     * untouched. A no-op when the layout has no enter key.
     */
    fun withEnterAlternates(
        layout: KeyboardLayout,
        alts: List<String> = ENTER_ALTERNATES,
    ): KeyboardLayout {
        if (alts.isEmpty()) return layout
        val keys = layout.keys.map { k ->
            if (k.type == KeyType.ENTER) k.copy(alternates = alts) else k
        }
        return layout.copy(keys = keys)
    }

    /**
     * Gives the shift key its [SHIFT_CASE_CELLS] popup. Always applied in the alpha-layout
     * chain: no layout JSON gives shift alternates, so nothing else starts its hold timer.
     * `hint = ""` keeps the corner clear, because [Key.hintChar] falls back to the first
     * alternate and every shift key would otherwise show a permanent `abc`.
     */
    fun withShiftCaseCells(
        layout: KeyboardLayout,
        cells: List<String> = SHIFT_CASE_CELLS,
    ): KeyboardLayout {
        if (cells.isEmpty()) return layout
        val keys = layout.keys.map { k ->
            if (k.type == KeyType.SHIFT) k.copy(alternates = cells, hint = "") else k
        }
        return layout.copy(keys = keys)
    }

    /**
     * Replaces the period and comma keys' long-press alternates with the user's own lists. An
     * empty list leaves that key untouched, so the layout JSON stays the source of truth: a
     * language layout may author its own punctuation, and a global default would override it
     * silently.
     *
     * Applied early in the alpha-layout chain, before [withEmojiOnComma] and [withCommaKey], so a
     * user list still gets the emoji entry prepended and survives the comma being repurposed
     * (see [ownCharFirst]).
     */
    fun withPunctuationAlternates(
        layout: KeyboardLayout,
        period: List<String>,
        comma: List<String>,
    ): KeyboardLayout {
        if (period.isEmpty() && comma.isEmpty()) return layout
        var changed = false
        val keys = layout.keys.map { k ->
            if (k.type != KeyType.CHAR) return@map k
            val replacement = when (k.output) {
                "." -> period
                "," -> comma
                else -> return@map k
            }
            if (replacement.isEmpty() || replacement == k.alternates) {
                k
            } else {
                changed = true
                k.copy(alternates = replacement)
            }
        }
        return if (changed) layout.copy(keys = keys) else layout
    }

    /**
     * Repurposes the comma key per the comma-key setting.
     *
     * [mode]: "keep" | "remove" | "char" | "text" | "paste" | "select_all"; [custom] backs the
     * char and text modes and is ignored otherwise. Callers pass pre-coerced values
     * (KeyboardConfig turns invalid combinations back into "keep").
     */
    fun withCommaKey(layout: KeyboardLayout, mode: String, custom: String): KeyboardLayout =
        withPunctuationKey(layout, ",", mode, custom, rehomeTo = ".")

    /**
     * The same for the period key.
     *
     * Applied after [withCommaKey]: removing the comma rehomes its emoji alternate onto the
     * period, so removing both keys drops that alternate. That is the accepted cost, and the
     * emoji key setting is the way back.
     */
    fun withPeriodKey(layout: KeyboardLayout, mode: String, custom: String): KeyboardLayout =
        withPunctuationKey(layout, ".", mode, custom, rehomeTo = ",")

    /**
     * Repurposes the punctuation key whose output is [target].
     *
     * Where the key remains, its emoji option and punctuation popup are kept and [target] becomes
     * the first plain alternate, so the character stays reachable from the same position. On
     * removal the spacebar absorbs the freed width and an emoji alternate hosted here moves to
     * [rehomeTo].
     */
    private fun withPunctuationKey(
        layout: KeyboardLayout,
        target: String,
        mode: String,
        custom: String,
        rehomeTo: String,
    ): KeyboardLayout {
        if (mode == "keep") return layout
        val key = layout.keys.firstOrNull { it.type == KeyType.CHAR && it.output == target }
            ?: return layout

        if (mode == "remove") {
            val keys = ArrayList<Key>(layout.keys.size)
            for (k in layout.keys) {
                when {
                    k === key -> {}
                    k.type == KeyType.SPACE && sameRow(k, key) ->
                        // Absorb the freed width; covers both the standard
                        // adjacent slot and mirrored layouts.
                        k.copy(
                            x = minOf(k.x, key.x),
                            w = k.w + key.w,
                        ).let { keys.add(it) }
                    k.type == KeyType.CHAR && k.output == rehomeTo &&
                        key.alternates.contains(EMOJI_ALTERNATE) ->
                        keys.add(k.copy(alternates = listOf(EMOJI_ALTERNATE) + k.alternates))
                    else -> keys.add(k)
                }
            }
            return layout.copy(keys = keys)
        }

        val (label, output) = when (mode) {
            "char" -> custom.take(1) to custom.take(1)
            "text" -> custom to custom
            // ISO/IEC 9995-7 paste symbol; select-all has no ISO glyph, a
            // short text label shrinks like any multi-char key label.
            "paste" -> "\u2398" to ACTION_PASTE
            "select_all" -> "ALL" to ACTION_SELECT_ALL
            else -> return layout
        }
        if (output.isEmpty()) return layout
        val keys = layout.keys.map { k ->
            if (k === key) {
                k.copy(label = label, output = output, alternates = ownCharFirst(k.alternates, target))
            } else {
                k
            }
        }
        return layout.copy(keys = keys)
    }

    /** [ch] joins the popup right after a leading emoji entry, if any. */
    private fun ownCharFirst(alternates: List<String>, ch: String): List<String> =
        if (alternates.firstOrNull() == EMOJI_ALTERNATE) {
            listOf(EMOJI_ALTERNATE, ch) + alternates.drop(1)
        } else {
            listOf(ch) + alternates
        }

    private fun sameRow(a: Key, b: Key): Boolean = kotlin.math.abs(a.y - b.y) < 0.01f

    /**
     * Puts the emoji picker first in the comma key's long-press popup. The spacebar keeps its
     * full width: an emoji key carved out of it cost swipe space and reflowed the bottom row
     * whenever the setting flipped.
     */
    fun withEmojiOnComma(layout: KeyboardLayout): KeyboardLayout {
        val keys = layout.keys.map { k ->
            if (k.type == KeyType.CHAR && k.output == ",") {
                k.copy(alternates = listOf(EMOJI_ALTERNATE) + k.alternates)
            } else {
                k
            }
        }
        return layout.copy(keys = keys)
    }

    /**
     * Non-QWERTY letter arrangements as a swap of one pair of letters: QWERTZ (German, Swiss)
     * exchanges Y and Z, QZERTY (the Italian typewriter arrangement) Z and W.
     */
    const val ARRANGEMENT_QWERTY = "qwerty"
    const val ARRANGEMENT_QWERTZ = "qwertz"
    const val ARRANGEMENT_QZERTY = "qzerty"

    /**
     * AZERTY is not a swap: it is served as its own layout file (azerty_fr.json, via
     * AlphaLayouts.name), so [withLetterArrangement] has nothing to do for it and the value
     * exists for the setting to carry.
     */
    const val ARRANGEMENT_AZERTY = "azerty"

    /**
     * Swaps two letters without touching the geometry, so the swipe decoder sees the keyboard
     * the user is looking at. One mutation covers every language; a qwertz_it.json would
     * duplicate every Italian accent for the sake of one moved pair.
     *
     * - The letter carries its id, label, output and accented alternates: "y" keeps "ý" and "ÿ".
     * - The position keeps x/y/w/h and its non-letter alternates, so
     *   [EdgeSwipeBindings.withImplicitAlternates] still finds 1-0 along the top row and
     *   [Key.hintChar] keeps every corner hint as authored.
     *
     * A layout that declares [KeyboardLayout.fixedArrangement] is returned untouched, so a QWERTZ
     * or QZERTY setting never permutes an AZERTY board.
     */
    fun withLetterArrangement(layout: KeyboardLayout, arrangement: String): KeyboardLayout {
        if (layout.fixedArrangement) return layout
        val pair = when (arrangement) {
            ARRANGEMENT_QWERTZ -> "y" to "z"
            ARRANGEMENT_QZERTY -> "z" to "w"
            else -> return layout
        }
        val first = layout.keys.firstOrNull { it.isLetter && it.output == pair.first }
        val second = layout.keys.firstOrNull { it.isLetter && it.output == pair.second }
        if (first == null || second == null) return layout

        fun swapped(host: Key, incoming: Key): Key {
            val letters = incoming.alternates.filter { it.firstOrNull()?.isLetter() == true }
            val symbols = host.alternates.filter { it.firstOrNull()?.isLetter() != true }
            return host.copy(
                id = incoming.id,
                label = incoming.label,
                output = incoming.output,
                alternates = letters + symbols,
                // An explicit hint belongs to the position's authored character, so it is
                // dropped; hintChar then falls back to the first alternate as on an unmutated
                // layout.
                hint = null,
            )
        }

        val keys = layout.keys.map { k ->
            when {
                k === first -> swapped(first, second)
                k === second -> swapped(second, first)
                else -> k
            }
        }
        return layout.copy(keys = keys)
    }

    /**
     * Drops accented letters from every key's long-press alternates, keeping digits and symbols:
     * English "a" offers `à á â ä ã å æ ā @`, eight accents before the one character an English
     * writer wants.
     *
     * A no-op for a layout that declares [KeyboardLayout.nativeAccents], which keeps "ñ" for
     * Spanish, "ą" for Polish and "ř" for Czech; the layout knows whether its accents belong to
     * its language. Safe before [withNumberPriority], which then finds nothing to reorder. A key
     * with no non-letter alternate keeps its list, so no popup goes empty.
     */
    fun withoutForeignAlternates(layout: KeyboardLayout): KeyboardLayout {
        if (layout.nativeAccents) return layout
        var changed = false
        val keys = layout.keys.map { k ->
            if (k.alternates.isEmpty()) return@map k
            val kept = k.alternates.filter { it.firstOrNull()?.isLetter() != true }
            if (kept.size == k.alternates.size || kept.isEmpty()) {
                k
            } else {
                changed = true
                k.copy(alternates = kept)
            }
        }
        return if (changed) layout.copy(keys = keys) else layout
    }

    /**
     * The user's own long-press list per letter (#8), matched by the letter typed so it
     * follows the key through any arrangement. Replaces the list and drops a layout hint, so the
     * list's first entry is the plain long-press, the up-swipe and the corner character. Last in
     * the alternates chain, so the key holds what the user wrote.
     */
    fun withLetterAlternates(layout: KeyboardLayout, lists: Map<Char, List<String>>): KeyboardLayout {
        if (lists.isEmpty()) return layout
        var changed = false
        val keys = layout.keys.map { k ->
            val list = if (k.isLetter) lists[k.output[0]] else null
            if (list.isNullOrEmpty() || (list == k.alternates && k.hint == null)) {
                k
            } else {
                changed = true
                k.copy(alternates = list, hint = null)
            }
        }
        return if (changed) layout.copy(keys = keys) else layout
    }

    /**
     * Reorders every key's long-press alternates so digits and symbols come before accented
     * letters ("Prioritize numbers over accents"): E offers 3 on a plain long-press instead of
     * è, and the corner hint follows because [Key.hintChar] derives from the first alternate.
     * Layout JSON is authored accents-first, so the off state needs no work.
     */
    fun withNumberPriority(layout: KeyboardLayout): KeyboardLayout {
        var changed = false
        val keys = layout.keys.map { k ->
            if (k.alternates.size < 2) return@map k
            val (symbols, accents) = k.alternates.partition { alt ->
                alt.firstOrNull()?.isLetter() != true
            }
            if (symbols.isEmpty() || accents.isEmpty()) {
                k
            } else {
                changed = true
                k.copy(alternates = symbols + accents)
            }
        }
        return if (changed) layout.copy(keys = keys) else layout
    }

    /**
     * One row in the layouts' own units: every alpha layout gives each row `h: 0.25`, so the
     * numbers row adds a quarter to the board.
     */
    const val NUMBER_ROW_H = 0.25f

    /** How much taller the board is with the numbers row, so no letter key changes size. */
    const val NUMBER_ROW_GROWTH = 1f + NUMBER_ROW_H

    /**
     * A row of digits above the letters. The rows below keep their share of a board one
     * row taller, which the service grows by [NUMBER_ROW_GROWTH], so a letter key keeps its size
     * and every distance in key widths is unchanged. A digit is not a letter key, so
     * no gesture starts on it: taps only.
     */
    fun withNumberRow(layout: KeyboardLayout): KeyboardLayout {
        val scale = 1f / NUMBER_ROW_GROWTH
        val moved = layout.keys.map { it.copy(y = (it.y + NUMBER_ROW_H) * scale, h = it.h * scale) }
        val digits = "1234567890".mapIndexed { i, c ->
            Key(
                id = "num_$c", type = KeyType.CHAR, label = c.toString(), output = c.toString(),
                x = i * 0.1f, y = 0f, w = 0.1f, h = NUMBER_ROW_H * scale,
            )
        }
        return layout.copy(keys = digits + moved)
    }

    /** What the top row's long-press offers with the numbers row on, left to right: Gboard's set. */
    val NUMBER_ROW_SYMBOLS = listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}")

    /**
     * With the numbers row on, the top letter row's digits duplicate the row above: they leave
     * its long-press lists and each key offers a symbol first, which is also its corner character
     * and its up-swipe. Accents stay; a layout's own digit hint gives way.
     */
    fun withNumberRowSymbols(layout: KeyboardLayout): KeyboardLayout {
        val letters = layout.keys.filter { it.isLetter }
        val topY = letters.minOfOrNull { it.y } ?: return layout
        val top = letters.filter { kotlin.math.abs(it.y - topY) < 0.01f }.sortedBy { it.x }.map { it.id }
        val keys = layout.keys.map { k ->
            val i = top.indexOf(k.id)
            if (i < 0) return@map k
            val kept = k.alternates.filter { !(it.length == 1 && it[0].isDigit()) }
            val symbol = NUMBER_ROW_SYMBOLS.getOrNull(i)
            val alts = if (symbol == null || symbol in kept) kept else listOf(symbol) + kept
            k.copy(alternates = alts, hint = k.hint?.takeUnless { it.length == 1 && it[0].isDigit() })
        }
        return layout.copy(keys = keys)
    }
}
