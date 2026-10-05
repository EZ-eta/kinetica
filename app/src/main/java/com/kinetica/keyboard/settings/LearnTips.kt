package com.kinetica.keyboard.settings

/**
 * Where a Learn Kinetica tip leads: the screen that holds the setting it names, null
 * for the top level, and the row to flash there. A tip with no entry is text only.
 */
object LearnTips {

    data class Target(val screenKey: String?, val key: String)

    val TARGETS: Map<String, Target> = mapOf(
        "tip_cursor" to Target("pref_group_gestures_more", "pref_spacebar_word_slide"),
        "tip_accents" to Target("pref_group_keys", "pref_group_longpress"),
        "tip_delete_words" to Target("pref_group_gestures_more", "pref_backspace_char_slide"),
        "tip_digits" to Target("pref_group_gestures_more", "pref_alternate_swipes"),
        "tip_resize" to Target("pref_group_size", "pref_keyboard_height_pct"),
        "tip_next_word" to Target("pref_group_typing", "pref_next_word"),
        "tip_recent" to Target("pref_group_bar", "pref_recent_words"),
        "tip_retype" to Target("pref_group_bar", "pref_retype_button"),
        "tip_autospace" to Target("pref_group_spacing", "pref_autospace"),
        "tip_double_space" to Target("pref_group_spacing", "pref_double_space_period"),
        "tip_enter" to Target("pref_group_typing", "pref_enter_action"),
        "tip_languages" to Target("pref_group_languages", "pref_enabled_languages"),
        "tip_follow_language" to Target("pref_group_languages_more", "pref_no_primary"),
        "tip_peck" to Target("pref_group_typing", "pref_peck_mode"),
        "tip_rank" to Target("pref_group_learning", "pref_reinforce_step_dp"),
        "tip_chords" to Target("pref_group_gestures", "pref_chords"),
        "tip_edge_swipes" to Target("pref_group_gestures", "pref_edge_swipes_screen"),
        "tip_expansions" to Target("pref_group_gestures", "pref_expansions_screen"),
        "tip_letter_lists" to Target("pref_group_longpress", "pref_edit_from_menu"),
        "tip_shortcut_row" to Target("pref_group_bar", "pref_bar_actions"),
        "tip_ctrl" to Target("pref_group_gestures", "pref_chords"),
        "tip_backup" to Target(null, "pref_backup_screen"),
        "tip_learned" to Target(null, "pref_dictionary_screen"),
        "tip_bigger_dictionary" to Target(null, "pref_dictionary_screen"),
        "tip_layouts" to Target("pref_group_size", "pref_layout_mode"),
    )
}
