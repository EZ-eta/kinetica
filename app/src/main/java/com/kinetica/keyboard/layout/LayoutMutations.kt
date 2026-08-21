package com.kinetica.keyboard.layout

import com.kinetica.keyboard.keys.EditorAction

/** Optional, settings-driven edits applied to a loaded layout. */
object LayoutMutations {

    /** Reserved alternate string: committing it opens the emoji picker. */
    const val EMOJI_ALTERNATE = "☺"

    /**
     * Reserved key outputs for editor actions, defined once in [EditorAction] so
     * the comma key and the chord shortcuts cannot disagree about what one means.
     * The IME intercepts them before the commit path (same pattern as
     * [EMOJI_ALTERNATE]).
     */
    val ACTION_PASTE = EditorAction.PASTE.output
    val ACTION_SELECT_ALL = EditorAction.SELECT_ALL.output

    /**
     * Enter's alternate-popup cells. Enter carries no alternates
     * in any layout JSON, so these are injected uniformly for every alpha
     * layout; the popup shows them alone (no base glyph) with the first
     * pre-selected, matching the default enter up-swipe output "?".
     */
    val ENTER_ALTERNATES = listOf("?", "!", ",")

    /** Id of the optional apostrophe key. */
    const val APOSTROPHE_KEY_ID = "apostrophe"

    /**
     * Home-row nudge applied together with the apostrophe key: the letter home
     * row (a..l, y=0.25) shifts left by this fraction of the keyboard width,
     * spending the slack left of "A" so the apostrophe - which stays at the
     * right edge - sits apart from "L" with a visible gap. Kept small so the
     * letter geometry barely moves (0.015 ≈ 0.15 kw, far inside the DTW radii,
     * and only while the key is enabled); raise it for a wider gap.
     */
    const val APOSTROPHE_HOME_ROW_SHIFT = 0.015f

    /**
     * Optional apostrophe key: a narrow, chromeless (no key background,
     * Nintype-style) CHAR key at the right of the home row, so "'" is reachable
     * by a single tap without the symbols layer or a long-press - the writing
     * path for elided/contracted words in any language (e.g. "nell'immagine",
     * "don't"). It is a non-letter key, so it stays invisible to the swipe
     * engine geometry (only a-z keys participate). The home row nudges left by
     * [APOSTROPHE_HOME_ROW_SHIFT] so the key sits apart from "L". Applied in the
     * alpha-layout chain only; idempotent (the appended key guards re-entry, so
     * the nudge is never doubled).
     */
    fun withApostropheKey(layout: KeyboardLayout): KeyboardLayout {
        if (layout.keys.any { it.id == APOSTROPHE_KEY_ID }) return layout
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
     * Gives the enter key its [ENTER_ALTERNATES] popup cells.
     * Applied unconditionally in the alpha-layout chain - the feature is always
     * on - and only to the alpha layer, so the numpad-layer enter (slide-left
     * back to letters) is untouched. A no-op when the layout has no enter key.
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
     * Replaces the period and comma keys' long-press alternates with the user's
     * own lists. An EMPTY list leaves that key untouched, so the layout JSON stays
     * the source of truth: all four bundled layouts happen to author the same
     * punctuation (period `... << >>`, comma `_ [ ] en-dash em-dash`), but a
     * future language layout may not, and a global default would have overridden
     * it silently.
     *
     * Applied EARLY in the alpha-layout chain, before [withEmojiOnComma] and
     * [withCommaKey], so a user list still gets the emoji entry prepended and
     * still survives the comma being repurposed (see [commaFirst]).
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
     * Repurposes the comma key per the comma-key setting. The
     * emoji long-press option and the comma's punctuation popup are preserved
     * where a key remains; "," itself becomes the first plain alternate so the
     * character stays reachable from the same position. On removal the
     * spacebar absorbs the freed width (the reverse of the old withEmojiKey
     * spacebar carve) and an emoji alternate hosted on the comma moves to the
     * period key rather than silently vanishing.
     *
     * [mode]: "keep" | "remove" | "char" | "text" | "paste" | "select_all";
     * [custom] backs the char/text modes and is ignored otherwise. Callers
     * pass pre-coerced values (KeyboardConfig blanks invalid combinations
     * back to "keep").
     */
    fun withCommaKey(layout: KeyboardLayout, mode: String, custom: String): KeyboardLayout {
        if (mode == "keep") return layout
        val comma = layout.keys.firstOrNull { it.type == KeyType.CHAR && it.output == "," }
            ?: return layout

        if (mode == "remove") {
            val keys = ArrayList<Key>(layout.keys.size)
            for (k in layout.keys) {
                when {
                    k === comma -> {}
                    k.type == KeyType.SPACE && sameRow(k, comma) ->
                        // Absorb the comma's width; covers both the standard
                        // left-adjacent slot and mirrored layouts.
                        k.copy(
                            x = minOf(k.x, comma.x),
                            w = k.w + comma.w,
                        ).let { keys.add(it) }
                    k.type == KeyType.CHAR && k.output == "." &&
                        comma.alternates.contains(EMOJI_ALTERNATE) ->
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
            "paste" -> "⎘" to ACTION_PASTE
            "select_all" -> "ALL" to ACTION_SELECT_ALL
            else -> return layout
        }
        if (output.isEmpty()) return layout
        val keys = layout.keys.map { k ->
            if (k === comma) {
                k.copy(label = label, output = output, alternates = commaFirst(k.alternates))
            } else {
                k
            }
        }
        return layout.copy(keys = keys)
    }

    /** "," joins the popup right after a leading emoji entry, if any. */
    private fun commaFirst(alternates: List<String>): List<String> =
        if (alternates.firstOrNull() == EMOJI_ALTERNATE) {
            listOf(EMOJI_ALTERNATE, ",") + alternates.drop(1)
        } else {
            listOf(",") + alternates
        }

    private fun sameRow(a: Key, b: Key): Boolean = kotlin.math.abs(a.y - b.y) < 0.01f

    /**
     * Puts the emoji picker first in the comma key's long-press popup (the
     * existing comma alternates shift right). The spacebar keeps its full
     * width: an emoji key carved out of it cost swipe-space and reflowed the
     * whole bottom row whenever the setting flipped.
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
     * Non-QWERTY letter arrangements, as a swap of ONE pair of letters:
     * QWERTZ (German, Swiss) exchanges Y and Z, QZERTY (the Italian typewriter
     * arrangement) exchanges Z and W.
     */
    const val ARRANGEMENT_QWERTY = "qwerty"
    const val ARRANGEMENT_QWERTZ = "qwertz"
    const val ARRANGEMENT_QZERTY = "qzerty"

    /**
     * Rearranges two letters without touching the geometry, so the swipe
     * decoder simply sees the keyboard the user is looking at.
     *
     * Done here rather than as a second set of layout JSON files because a
     * qwertz_it.json would have to duplicate every accent Italian carries, and
     * the pair that moves is the only difference. One mutation covers every
     * bundled language and every language added later.
     *
     * What travels with the LETTER: its id, label, output and its accented
     * alternates - "y" keeps "ý" and "ÿ" wherever it lands.
     * What stays with the POSITION: x/y/w/h and the non-letter alternates.
     * That second half is deliberate and it keeps two shipped features honest:
     * [EdgeSwipeBindings.withImplicitAlternates] reads the first non-letter
     * alternate per key, so the top row stays 1-0 in every arrangement instead
     * of offering an apostrophe where the 6 belongs; and [Key.hintChar] is the
     * first alternate, so rebuilding accents-first keeps every corner hint as
     * authored.
     *
     * AZERTY is deliberately absent. It moves M to the home row and changes
     * both row lengths, so it is a different layout rather than a swap and
     * needs its own JSON.
     */
    fun withLetterArrangement(layout: KeyboardLayout, arrangement: String): KeyboardLayout {
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
                // An explicit hint belongs to the position's own authored
                // character, so it is dropped rather than carried onto a letter
                // it was never written for; hintChar then falls back to the
                // first alternate exactly as on an unmutated layout.
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
     * Drops accented letters from every key's long-press alternates, keeping the
     * digits and symbols. In the English layout "a" offers
     * `à á â ä ã å æ ā @` — eight forms of a letter English does not accent
     * before the one character the key is really there for — and "o" carries
     * seven; a user who writes only English can reach neither `@` nor a digit
     * without walking past them.
     *
     * A no-op for a layout that declares [KeyboardLayout.nativeAccents], which is
     * what keeps this from taking "ñ" away from a Spanish writer or "ą" from a
     * Polish writer: the layout, not
     * this function, knows whether its accents belong to its language.
     *
     * Safe to run before [withNumberPriority] (which then finds nothing to
     * reorder): measured against all four bundled layouts, every
     * accent-carrying key has at least one non-letter alternate — `e`→`3`,
     * `a`→`@`, `s`→`#`, `l`→`)`, `z`→`'`, `c`→`;`, `n`→`!`, `y`→`6`, `u`→`7`,
     * `i`→`8`, `o`→`9` — so no key is left with an empty popup or without the
     * [Key.hintChar] its corner hint derives from.
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
     * Reorders every key's long-press alternates so digits and symbols come
     * before accented letters ("Prioritize numbers over accents"): E offers 3
     * as the plain-long-press default instead of è, and the key's 40% corner
     * hint follows because [Key.hintChar] derives from the first alternate.
     * Layout JSON is authored accents-first, so the OFF state needs no work.
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
}
