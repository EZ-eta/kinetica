package com.kinetica.keyboard.layout

data class KeyboardLayout(
    val name: String,
    val locale: String,
    val keys: List<Key>,
    /**
     * True when the accented letters in this layout's long-press alternates
     * belong to the layout's own language, so trimming them would take away
     * letters the user needs to write it (Italian "è", Spanish "ñ", Polish "ą",
     * Czech "ř").
     *
     * Declared by the layout JSON rather than inferred from [locale], because
     * the fact is about the alternates an author chose and not about the code:
     * the English layout carries eight accented forms on "a" and needs none of
     * them, while a future language layout exists precisely to carry its own.
     * Absent means false, which is also the right answer for the plain "qwerty"
     * fallback any unregistered language is served.
     *
     * Read only by [LayoutMutations.withoutForeignAlternates]; the swipe engine
     * never sees alternates at all.
     */
    val nativeAccents: Boolean = false,
    /**
     * True when this layout's letter arrangement is the point of the file and
     * must not be permuted. AZERTY is the case: it moves M to the home row and
     * runs rows of 10/10/6 against QWERTY's 10/9/7, so applying the QWERTZ or
     * QZERTY letter swap on top of it would move letters the arrangement
     * already placed and leave the board neither AZERTY nor QWERTZ.
     *
     * Read only by [LayoutMutations.withLetterArrangement]. Absent means false,
     * which is right for every QWERTY-derived layout, where the swap is exactly
     * what the arrangement setting is for.
     */
    val fixedArrangement: Boolean = false,
)
