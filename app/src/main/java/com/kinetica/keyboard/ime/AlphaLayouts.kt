package com.kinetica.keyboard.ime

import com.kinetica.keyboard.keys.ActionRow
import com.kinetica.keyboard.keys.NativeLetters
import com.kinetica.keyboard.layout.KeyboardLayout
import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.KeyboardConfig

/**
 * The alpha layer the keyboard shows: which asset, and the settings-driven mutations on top.
 * One place, because settings reads it too, to show each letter's built-in long-press list.
 */
object AlphaLayouts {

    /**
     * Alpha layer asset for [config]'s language, among [bundledLayouts].
     *
     * An arrangement that is not a letter swap is its own file. AZERTY is the only one: M on
     * the home row and rows of 10/10/6, which [LayoutMutations.withLetterArrangement]
     * declines. The setting is global and the file per-language, so a non-French language on
     * "azerty" finds no azerty_<lang>.json and falls through to its ordinary layout.
     */
    fun name(config: KeyboardConfig, bundledLayouts: Set<String>): String {
        // A language in another script has one board, its own, whatever the Latin
        // arrangement setting says: there is no QWERTY to swap letters on.
        val native = "native_${config.language}"
        if (native in bundledLayouts) return native
        val arranged = "${config.keyArrangement}_${config.language}"
        if (arranged in bundledLayouts) return arranged
        val name = "qwerty_${config.language}"
        return if (name in bundledLayouts) name else "qwerty"
    }

    /**
     * [base] with every settings-driven mutation applied, in the order they compose. [field] is
     * the kind of field the board is for; plain text for the settings screen's previews.
     */
    fun build(base: KeyboardLayout, config: KeyboardConfig, field: FieldKind = FieldKind.TEXT): KeyboardLayout {
        var l = base
        // First in the chain: every mutation below matches keys by output or
        // by id, so they must see the letters where the user will.
        l = LayoutMutations.withLetterArrangement(l, config.keyArrangement)
        // Enter's held/slide-up alternate popup is always on; the
        // symbols are settings-configurable (first is the primary).
        l = LayoutMutations.withEnterAlternates(l, config.enterAlternates.map(::cell))
        // Shift's case popup, always on and invisible until held.
        l = LayoutMutations.withShiftCaseCells(l)
        // Before the emoji and comma-role mutations, so a user list still gets
        // the emoji entry prepended and still survives a repurposed comma.
        l = LayoutMutations.withPunctuationAlternates(
            l, config.periodAlternates.map(::cell), config.commaAlternates.map(::cell),
        )
        // Optional apostrophe key in the home-row right padding.
        if (config.apostropheKey) l = LayoutMutations.withApostropheKey(l)
        // After the apostrophe key, which the spread stops at; before the numbers row, which moves
        // every row and leaves none at y=0.25.
        if (config.homeRowSpreadPct > 0) l = LayoutMutations.withHomeRowSpread(l, config.homeRowSpreadPct / 100f)
        if (config.emojiKey) l = LayoutMutations.withEmojiOnComma(l)
        // Before the reorder: with the accents gone number-priority has nothing to move, so
        // the two settings compose.
        if (config.plainLetterAlternates) {
            l = LayoutMutations.withoutForeignAlternates(l, NativeLetters.keptBy(config.enabledLanguages))
        }
        if (config.numberPriority) l = LayoutMutations.withNumberPriority(l)
        // Digits have their own row then; a user's list below still wins.
        if (config.numberRow) l = LayoutMutations.withNumberRowSymbols(l)
        // Last in the alternates chain, so a list the user wrote wins (#8). An action written
        // by name (`:paste`) becomes the symbol that runs it.
        val lists = config.letterAlternates.mapValues { (_, list) -> list.map(::cell) }
        l = LayoutMutations.withLetterAlternates(l, lists)
        // After the emoji mutation, so a removal can relocate the emoji
        // alternate and a repurposed key keeps it in its popup.
        // A field-fitting key only stands in for a comma the user kept: a comma they removed or
        // repurposed stays as they set it. It goes through the custom character's path, so `,`
        // stays first in the key's menu and the period is untouched.
        val fieldChar = field.commaChar.takeIf { config.fieldKeys && config.commaMode == KEEP_MODE }
        if (fieldChar != null) {
            l = LayoutMutations.withCommaKey(l, "char", fieldChar)
            if (field == FieldKind.URL) l = LayoutMutations.withCommaAlternateAfterComma(l, ":")
        } else {
            l = LayoutMutations.withCommaKey(l, config.commaMode, config.commaCustom)
        }
        // After the comma, so removing both keys is defined: the comma hands its emoji
        // alternate to the period, which has nowhere left to pass it.
        l = LayoutMutations.withPeriodKey(l, config.periodMode, config.periodCustom)
        // The same rule for the period: its menu opens on domain endings only where it was kept.
        if (config.fieldKeys && config.periodMode == KEEP_MODE) {
            l = LayoutMutations.withPeriodAlternatesFirst(l, field.domainEndings)
        }
        // Last: it moves every key, and the mutations above find keys by row.
        if (config.numberRow) {
            l = LayoutMutations.withNumberRow(l)
            l = LayoutMutations.withNumberRowLists(l, lists)
        }
        return l
    }

    /**
     * A list entry as its cell: an action written by name (`:paste`) or as a target
     * (`action:paste`) becomes the symbol that runs it, in every list a user writes.
     */
    private fun cell(entry: String): String = ActionRow.glyphForToken(entry) ?: entry

    private const val KEEP_MODE = "keep"
}
