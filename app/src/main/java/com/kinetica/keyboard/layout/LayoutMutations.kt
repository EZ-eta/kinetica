package com.kinetica.keyboard.layout

/** Optional, settings-driven edits applied to a loaded layout. */
object LayoutMutations {

    /** Reserved alternate string: committing it opens the emoji picker. */
    const val EMOJI_ALTERNATE = "☺"

    /**
     * Reserved key outputs for editor actions: a "action:"
     * prefix can never collide with typeable text, and the IME intercepts
     * these before the commit path (same pattern as [EMOJI_ALTERNATE]).
     */
    const val ACTION_PASTE = "action:paste"
    const val ACTION_SELECT_ALL = "action:select_all"

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
        return KeyboardLayout(layout.name, layout.locale, shifted + apos)
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
        return KeyboardLayout(layout.name, layout.locale, keys)
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
            return KeyboardLayout(layout.name, layout.locale, keys)
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
        return KeyboardLayout(layout.name, layout.locale, keys)
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
        return KeyboardLayout(layout.name, layout.locale, keys)
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
        return if (changed) KeyboardLayout(layout.name, layout.locale, keys) else layout
    }
}
