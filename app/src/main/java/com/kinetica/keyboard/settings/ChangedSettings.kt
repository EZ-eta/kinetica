package com.kinetica.keyboard.settings

/**
 * Which settings differ from their default, and what a reset writes.
 *
 * A basic user who changed something and cannot find it again needs one list with a way back.
 * Pure, so the rules are tested on maps: [defaults] are the XML's own defaults as the rows report
 * them, [stored] the values the preferences hold now.
 */
internal object ChangedSettings {

    /**
     * Settings the keyboard writes along with the language. Listed, never reset: resetting the
     * language alone can leave a language active that is not among the enabled ones.
     */
    val KEYBOARD_MANAGED: Set<String> = setOf(Prefs.LANGUAGE, Prefs.ENABLED_LANGUAGES, Prefs.KEY_ARRANGEMENT)

    /** The default of [key]: the XML's when it has one, else the code's, else null (unknown). */
    fun defaultOf(key: String, defaults: Map<String, Any?>): Any? = when {
        defaults.containsKey(key) -> defaults[key]
        Prefs.dayKey(key) == Prefs.THEME_COLOR_HEX || Prefs.dayKey(key) == Prefs.TRAIL_COLOR_HEX -> Prefs.colorHexDefault(key)
        else -> null
    }

    /**
     * Whether [stored] differs from [default]. A text row with no default starts empty, so any
     * text in it is a change; a value of another kind with no default cannot be judged and is
     * left out. Colours compare without case, as `HexColor` reads them.
     */
    fun differs(stored: Any?, default: Any?): Boolean = when {
        // Never written: the row shows its default.
        stored == null -> false
        default == null -> stored is String && stored.isNotEmpty()
        stored is String && default is String && stored.startsWith("#") -> !stored.equals(default, ignoreCase = true)
        stored is Set<*> && default is Set<*> -> stored.toSet() != default.toSet()
        else -> stored != default
    }

    /** The entries of [tree], in its order, whose stored value differs from its default. */
    fun changed(
        tree: List<SettingsIndex.Entry>,
        defaults: Map<String, Any?>,
        stored: Map<String, *>,
    ): List<SettingsIndex.Entry> = tree
        .filter { differs(stored[it.key], defaultOf(it.key, defaults)) }
        .distinctBy { it.key }

    /** Whether a reset may touch [key]. */
    fun resettable(key: String): Boolean = key !in KEYBOARD_MANAGED
}
