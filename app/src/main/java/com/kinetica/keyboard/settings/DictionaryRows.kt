package com.kinetica.keyboard.settings

import java.text.Collator
import java.util.Locale

/**
 * The Dictionary screen's order, pure so it can be tested: languages by their name as the phone's
 * language sorts it, so `Čeština` sits with the Cs and not after `Polski`.
 */
object DictionaryRows {

    fun <T> byName(rows: List<T>, label: (T) -> String, locale: Locale): List<T> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return rows.sortedWith { a, b -> collator.compare(label(a), label(b)) }
    }
}
