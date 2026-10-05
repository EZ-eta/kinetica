package com.kinetica.keyboard.keys

import android.view.KeyEvent

/**
 * A key with modifiers held, as a shortcut's target: Ctrl+A, Ctrl+Del. Stored as text,
 * `combo:ctrl+a`, so a chord, an edge swipe and an expansion carry it the way they carry an
 * `action:` name, and the editor decides what it means, as with a hardware keyboard.
 */
data class KeyCombo(val ctrl: Boolean, val shift: Boolean, val alt: Boolean, val key: String) {

    fun encode(): String = PREFIX + (modifierNames() + key).joinToString("+")

    /** As a person reads it: `Ctrl+A`, `Ctrl+Shift+Left`. */
    fun label(): String = (modifierNames().map { it.replaceFirstChar(Char::uppercaseChar) } + keyLabel(key)).joinToString("+")

    private fun modifierNames(): List<String> = listOfNotNull("ctrl".takeIf { ctrl }, "shift".takeIf { shift }, "alt".takeIf { alt })

    /** The key code this combo presses. */
    fun keyCode(): Int = keyCodeOf(key) ?: KeyEvent.KEYCODE_UNKNOWN

    /** The meta state the key event carries. */
    fun metaState(): Int {
        var m = 0
        if (ctrl) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (alt) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return m
    }

    companion object {
        const val PREFIX = "combo:"

        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
        private const val DIGITS = "0123456789"

        /** The keys that type nothing, by the name a combo uses. */
        val SPECIAL: Map<String, Int> = linkedMapOf(
            "backspace" to KeyEvent.KEYCODE_DEL,
            "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "tab" to KeyEvent.KEYCODE_TAB,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "escape" to KeyEvent.KEYCODE_ESCAPE,
            "space" to KeyEvent.KEYCODE_SPACE,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "home" to KeyEvent.KEYCODE_MOVE_HOME,
            "end" to KeyEvent.KEYCODE_MOVE_END,
            "pageup" to KeyEvent.KEYCODE_PAGE_UP,
            "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
        )

        /** The key code for a combo key: a Latin letter, a digit or a [SPECIAL] name; null otherwise. */
        fun keyCodeOf(key: String): Int? {
            SPECIAL[key]?.let { return it }
            val c = key.singleOrNull() ?: return null
            LETTERS.indexOf(c).takeIf { it >= 0 }?.let { return KeyEvent.KEYCODE_A + it }
            DIGITS.indexOf(c).takeIf { it >= 0 }?.let { return KeyEvent.KEYCODE_0 + it }
            return null
        }

        private fun keyLabel(key: String): String = when (key) {
            "pageup" -> "Page up"
            "pagedown" -> "Page down"
            else -> if (key.length == 1) key.uppercase() else key.replaceFirstChar(Char::uppercaseChar)
        }

        /** The combo [text] names, or null when it is not one this build can send. */
        fun parse(text: String): KeyCombo? {
            if (!text.startsWith(PREFIX)) return null
            val parts = text.substring(PREFIX.length).lowercase().split('+')
            if (parts.isEmpty()) return null
            val key = parts.last()
            val mods = parts.dropLast(1)
            if (mods.any { it !in MODIFIERS } || mods.size != mods.toSet().size) return null
            if (keyCodeOf(key) == null) return null
            return KeyCombo("ctrl" in mods, "shift" in mods, "alt" in mods, key)
        }

        /**
         * Names a typed key may use beside [SPECIAL]'s own. Del is forward delete, as on a PC
         * keyboard, so Ctrl+Del deletes the next word.
         */
        private val TYPED_NAMES = mapOf(
            "del" to "delete", "canc" to "delete", "bksp" to "backspace", "\u232B" to "backspace",
            "return" to "enter", "esc" to "escape", "page up" to "pageup", "pgup" to "pageup",
            "page down" to "pagedown", "pgdn" to "pagedown",
        )

        /**
         * The Ctrl combination a key typed after "Ctrl +" names: `a`, `Del`, `shift+left`. Null
         * when it names no key this build can send, or a modifier other than Shift and Alt.
         */
        fun typed(text: String): KeyCombo? {
            val parts = text.trim().lowercase().split('+').map { it.trim() }
            if (parts.any { it.isEmpty() }) return null
            val mods = parts.dropLast(1)
            if (mods.any { it != "shift" && it != "alt" } || mods.size != mods.toSet().size) return null
            val key = parts.last().let { TYPED_NAMES[it] ?: it }
            if (keyCodeOf(key) == null) return null
            return KeyCombo(ctrl = true, shift = "shift" in mods, alt = "alt" in mods, key = key)
        }

        /** What the "Ctrl +" field shows for [combo]: the text [typed] reads back to it. */
        fun typedOf(combo: KeyCombo): String =
            (listOfNotNull("Shift".takeIf { combo.shift }, "Alt".takeIf { combo.alt }) +
                if (combo.key == "delete") "Del" else keyLabel(combo.key)).joinToString("+")

        /** A combo for [key] with Ctrl, as the one-shot Ctrl sends it; null for a key with no code. */
        fun ctrlOf(key: String): KeyCombo? = keyCodeOf(key)?.let { KeyCombo(ctrl = true, shift = false, alt = false, key = key) }

        private val MODIFIERS = setOf("ctrl", "shift", "alt")
    }
}
