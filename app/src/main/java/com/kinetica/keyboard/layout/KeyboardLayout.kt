package com.kinetica.keyboard.layout

import com.kinetica.keyboard.engine.Alphabet

data class KeyboardLayout(
    val name: String,
    val locale: String,
    val keys: List<Key>,
    /**
     * True when the accented letters in this layout's long-press alternates belong to its own
     * language, so trimming them would remove letters the user needs (Italian "è", Spanish "ñ",
     * Polish "ą", Czech "ř").
     *
     * Declared in the JSON, not inferred from [locale]: it describes the alternates an author
     * chose, and the English layout carries eight accented forms on "a" and needs none. Absent
     * means false, right for the plain "qwerty" fallback too. Read only by
     * [LayoutMutations.withoutForeignAlternates]; the swipe engine never sees alternates.
     */
    val nativeAccents: Boolean = false,
    /**
     * True when the letter arrangement is the point of the file and must not be permuted.
     * AZERTY moves M to the home row and runs rows of 10/10/6 against QWERTY's 10/9/7, so a
     * QWERTZ or QZERTY swap on top would leave it neither.
     *
     * Read only by [LayoutMutations.withLetterArrangement]. Absent means false, right for every
     * QWERTY-derived layout.
     */
    val fixedArrangement: Boolean = false,
    /**
     * The script the board's letters are written in (`"script"` in the JSON), Latin when
     * absent. Every key of the layout carries it, so [Key.isLetter] reads the right letters.
     */
    val alphabet: Alphabet = Alphabet.LATIN,
)
