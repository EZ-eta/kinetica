package com.kinetica.keyboard.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * Which enabled languages suggest: the active one alone, the next one mixed in behind it, or up to
 * three as equals following the text. One choice, since with both old switches on mix did nothing.
 */
enum class LanguageMode(val value: String) {
    ONE("one"),
    MIX("mix"),
    FOLLOW("follow"),
    ;

    companion object {
        fun fromPref(value: String?): LanguageMode = when (value) {
            MIX.value -> MIX
            FOLLOW.value -> FOLLOW
            else -> ONE
        }

        /**
         * The mode the two old switches in [stored] stand for, or null when neither is there. Follow
         * wins over mix: with both on, follow alone ran.
         */
        fun fromLegacy(stored: Map<String, *>): LanguageMode? {
            if (Prefs.NO_PRIMARY !in stored && Prefs.AUTO_DETECT_LANGUAGE !in stored) return null
            return when {
                stored[Prefs.NO_PRIMARY] as? Boolean == true -> FOLLOW
                stored[Prefs.AUTO_DETECT_LANGUAGE] as? Boolean == true -> MIX
                else -> ONE
            }
        }

        /**
         * Turns the old switches into the mode and removes them. They win over a stored mode: present,
         * they are either not migrated yet or brought back by an old backup, so the order of the
         * callers never matters.
         */
        @SuppressLint("ApplySharedPref") // the config read right after must see the mode
        fun migrate(prefs: SharedPreferences) {
            val mode = fromLegacy(prefs.all) ?: return
            prefs.edit()
                .putString(Prefs.LANGUAGE_MODE, mode.value)
                .remove(Prefs.NO_PRIMARY)
                .remove(Prefs.AUTO_DETECT_LANGUAGE)
                .commit()
        }
    }
}
