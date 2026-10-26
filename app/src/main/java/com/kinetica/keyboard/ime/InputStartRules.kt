package com.kinetica.keyboard.ime

import com.kinetica.keyboard.layout.LayoutMutations
import com.kinetica.keyboard.settings.Prefs

/**
 * Whether the language preferences need writing at all.
 *
 * Beside [languageOnInputStart] and pure for the same reason: it is asked on every input
 * start, and the answer is no every time nothing has moved.
 */
internal fun languageSyncNeedsWrite(
    storedLanguage: String?,
    syncedLanguage: String?,
    language: String,
): Boolean = storedLanguage != language || syncedLanguage != language

/**
 * Choose the initial language without losing Settings edits made while the
 * service was absent. An existing preference also wins on the first sync;
 * once acknowledged, Android can select a different subtype on a cold start.
 *
 * [followSystem] off is the opt-out. Android gives the system locale's subtype to a user who never
 * forced one in its picker, so with stored and synced agreeing an English phone went back to
 * English at every cold start. Off, a stored choice always wins, and only a first run with
 * nothing stored takes the subtype.
 */
internal fun languageOnInputStart(
    storedLanguage: String?,
    syncedLanguage: String?,
    subtypeLanguage: String?,
    followSystem: Boolean = true,
): String {
    if (storedLanguage != null && storedLanguage != syncedLanguage) return storedLanguage
    if (!followSystem) return storedLanguage ?: subtypeLanguage ?: Prefs.DEFAULT_LANGUAGE
    return subtypeLanguage ?: storedLanguage ?: Prefs.DEFAULT_LANGUAGE
}

/**
 * The Kinetica language code an Android subtype locale names, or null.
 *
 * Norwegian needs folding: `method.xml` declares `nb_NO`, the correct Bokmal locale, while
 * the asset and [Prefs.ALL_LANGUAGES] use `no`. Unfolded, Norwegian falls out of the sync and
 * Android's picker stores a code with no dictionary behind it.
 */
internal fun kineticaLanguageOf(subtypeLanguage: String?): String? = when (subtypeLanguage) {
    null -> null
    "nb", "nn" -> "no"
    // Android still names Hebrew by its legacy code.
    "iw" -> "he"
    else -> subtypeLanguage
}

/**
 * Which of the declared subtypes, by their [locales] in declaration order, belong to the
 * [languages] enabled here: the set Android should offer in its picker.
 */
internal fun subtypesFor(locales: List<String?>, languages: Collection<String>): List<Int> =
    locales.indices.filter { kineticaLanguageOf(locales[it]?.substringBefore('_')) in languages }

/**
 * What the active language does to the letter arrangement, and whether the value left in
 * the preference is this keyboard's or the user's.
 *
 * [arrangement] is null to leave the preference alone.
 */
internal data class ArrangementChange(val arrangement: String?, val autoApplied: Boolean)

/**
 * French is typed on AZERTY, its own layout file, so selecting the language moves the
 * arrangement setting to match.
 *
 * The setting is global while the file is per language: a value left behind by French would
 * show "AZERTY" in Settings beside a German QWERTZ board, since [AlphaLayouts.name] finds no
 * azerty_de.json and falls through. So the write is recorded and leaving French hands it back.
 * An arrangement the user chose is never touched: [autoApplied] marks this keyboard's write,
 * and a stale marker is dropped once the stored value is no longer the one written.
 */
internal fun arrangementOnLanguageChange(
    stored: String,
    autoApplied: Boolean,
    language: String,
): ArrangementChange {
    val azerty = LayoutMutations.ARRANGEMENT_AZERTY
    val plain = Prefs.DEFAULT_KEY_ARRANGEMENT
    if (language == "fr") {
        if (stored == plain) return ArrangementChange(azerty, true)
        return ArrangementChange(null, autoApplied && stored == azerty)
    }
    if (autoApplied && stored == azerty) return ArrangementChange(plain, false)
    return ArrangementChange(null, false)
}

/**
 * Whether a field opening should bring the number pad up: a number field, the setting on, and
 * not a restart. Off by default, since some type words into number fields and expand them.
 */
internal fun opensOnNumpad(restarting: Boolean, enabled: Boolean, numeric: Boolean): Boolean =
    enabled && !restarting && numeric

/**
 * Whether a field opening should bring the letters back. Not on a restart: an app restarting its
 * own field mid-edit keeps the page the user chose there.
 */
internal fun opensOnLetters(restarting: Boolean, enabled: Boolean, onLetters: Boolean): Boolean =
    enabled && !restarting && !onLetters
