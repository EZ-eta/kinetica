package com.kinetica.keyboard.keys

import com.kinetica.keyboard.layout.Key
import com.kinetica.keyboard.layout.KeyboardLayout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One user-configurable edge-swipe shortcut: key + direction -> action. */
data class EdgeSwipeBinding(
    val keyId: String,
    val direction: Direction,
    /** Text to insert, an action or combination, or [EdgeSwipeBindings.ACTION_EMOJI] for the picker. */
    val output: String,
) {
    enum class Direction { UP, DOWN, LEFT, RIGHT }
}

/**
 * The active edge-swipe binding set, persisted as one JSON preference and
 * consulted by [EdgeSwipeDetector] at pointer lift. Bindings are keyed on the
 * layout key id, which is stable across languages and layout modes.
 */
class EdgeSwipeBindings(val bindings: List<EdgeSwipeBinding>) {

    private val byKeyAndDir = HashMap<String, String>(bindings.size * 2)

    init {
        for (b in bindings) {
            byKeyAndDir["${b.keyId}/${b.direction}"] = b.output
        }
    }

    fun outputFor(keyId: String, direction: EdgeSwipeBinding.Direction): String? =
        byKeyAndDir["$keyId/$direction"]

    /**
     * By the key's id first, so every stored binding keeps working; then by the character it
     * types, which is what a typed trigger names. So `1` fires on the symbols page, the number
     * pad and the number row alike, and `,` on the comma key.
     */
    fun outputFor(key: Key, direction: EdgeSwipeBinding.Direction): String? =
        outputFor(key.id, direction) ?: key.chordChar?.let { outputFor(it.toString(), direction) }

    fun serialize(): String {
        val arr = JSONArray()
        for (b in bindings) {
            arr.put(
                JSONObject()
                    .put("key", b.keyId)
                    .put("dir", b.direction.name)
                    .put("out", b.output),
            )
        }
        return arr.toString()
    }

    companion object {
        /** Reserved output value: opens the emoji picker. */
        const val ACTION_EMOJI = "emoji"

        // Letter rows by normalized Key.y (top 0.00, home 0.25, bottom 0.50 in the bundled
        // layouts). Half-row bands tolerate layout rounding without catching the home row or the
        // space/enter row (0.75).
        private const val TOP_ROW_Y_MAX = 0.1f
        private const val BOTTOM_ROW_Y_MIN = 0.4f
        private const val BOTTOM_ROW_Y_MAX = 0.6f

        /**
         * Which built-in gesture a binding on [keyId] in [direction] would shadow, or null when it
         * is safe. Pure so the settings screen and the tests share one rule.
         *
         * The collisions are silent at runtime and the shipped defaults avoid them all, so only
         * someone who customises meets one, and the settings screen is where they can learn of it.
         *
         * Returns an identifier for the shadowed gesture, not a message: the strings live with
         * the screen that shows them.
         */
        fun shadowedGesture(keyId: String, direction: EdgeSwipeBinding.Direction): String? {
            val horizontal = direction == EdgeSwipeBinding.Direction.LEFT ||
                direction == EdgeSwipeBinding.Direction.RIGHT
            return when {
                // The spacebar slides to move the cursor; a horizontal binding on it competes
                // with every cursor move.
                keyId == "space" && horizontal -> SHADOWS_CURSOR_SLIDE
                // Backspace slides left to stage a deletion, the gesture most likely to be
                // triggered by accident.
                keyId == "backspace" && horizontal -> SHADOWS_STAGED_DELETE
                // ?123 slides sideways to reach the numpad and back. The key's id is "mode"; its
                // type is mode_symbols.
                keyId == "mode" && horizontal -> SHADOWS_LAYER_SLIDE
                // Enter slides left for its alternates popup. Up is not flagged: the shipped
                // default binds enter-up to "?", the popup's own primary, so the two agree.
                keyId == "enter" && horizontal -> SHADOWS_ENTER_POPUP
                // A horizontal binding on a letter key, in any script, competes with short typing
                // swipes, which is how the engine reads a two-letter word.
                keyId.length == 1 && keyId[0].isLetter() && horizontal ->
                    SHADOWS_TYPING_SWIPE
                else -> null
            }
        }

        /** The keys a typed trigger cannot name, offered as buttons: they type no character. */
        val SPECIAL_KEY_IDS = listOf("enter", "backspace", "shift", "mode")

        /**
         * What the typed field shows for a stored [keyId]: its character, or null for a key
         * that types none. `comma` and `period` were the ids a list could pick.
         */
        fun typedKeyFor(keyId: String): String? = when {
            keyId == "comma" -> ","
            keyId == "period" -> "."
            keyId in SPECIAL_KEY_IDS || keyId == "space" -> null
            keyId.length == 1 -> keyId
            else -> null
        }

        /**
         * The id a typed [key] is stored under. An edited binding keeps the id it had when its
         * key is unchanged, so `comma` stays `comma` and never moves to another key.
         */
        fun keyIdFor(key: Char, existing: String?): String =
            if (existing != null && typedKeyFor(existing) == key.toString()) existing else key.toString()

        const val SHADOWS_CURSOR_SLIDE = "cursor_slide"
        const val SHADOWS_STAGED_DELETE = "staged_delete"
        const val SHADOWS_LAYER_SLIDE = "layer_slide"
        const val SHADOWS_ENTER_POPUP = "enter_popup"
        const val SHADOWS_TYPING_SWIPE = "typing_swipe"

        /**
         * Synthesizes the implicit alternate-swipe layer from [layout] and layers [explicit] on
         * top so user bindings win: a swipe up on a top-row letter key, or down on a bottom-row
         * one, inserts the glyph that key draws in its corner. Home-row and non-letter keys get
         * nothing.
         *
         * The output is [Key.hintChar], so what a key shows is what it types under every
         * combination of the long-press settings. The first non-letter alternate would disagree
         * with the hint whenever a key lists an accent first: qwerty "z" draws `ž`.
         *
         * Precedence comes from ordering, not a second lookup: the implicit entries come first,
         * so an explicit "keyId/direction" appended after overwrites it in [byKeyAndDir]. The
         * result is runtime-only, so the implicit rows never reach the persisted preference.
         */
        fun withImplicitAlternates(
            layout: KeyboardLayout,
            explicit: EdgeSwipeBindings,
        ): EdgeSwipeBindings {
            val implicit = ArrayList<EdgeSwipeBinding>()
            for (k in layout.keys) {
                if (!k.isLetter) continue
                val direction = when {
                    k.y < TOP_ROW_Y_MAX -> EdgeSwipeBinding.Direction.UP
                    k.y in BOTTOM_ROW_Y_MIN..BOTTOM_ROW_Y_MAX -> EdgeSwipeBinding.Direction.DOWN
                    else -> continue
                }
                val symbol = k.hintChar ?: continue
                implicit.add(EdgeSwipeBinding(k.id, direction, symbol))
            }
            return EdgeSwipeBindings(implicit + explicit.bindings)
        }

        /**
         * The built-in shortcuts: two, on keys that draw no corner hint to contradict. Every
         * letter's swipe types its own hint ([withImplicitAlternates]); the emoji picker is on the
         * comma key's long-press.
         */
        val DEFAULTS = EdgeSwipeBindings(
            listOf(
                EdgeSwipeBinding("backspace", EdgeSwipeBinding.Direction.UP, "!"),
                EdgeSwipeBinding("enter", EdgeSwipeBinding.Direction.UP, "?"),
            ),
        )

        /** Parses the persisted JSON; null or malformed input -> defaults. */
        fun parse(json: String?): EdgeSwipeBindings {
            if (json == null) return DEFAULTS
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<EdgeSwipeBinding>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val dir = try {
                        EdgeSwipeBinding.Direction.valueOf(o.getString("dir"))
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                    val output = o.getString("out")
                    if (output.isEmpty()) continue
                    out.add(EdgeSwipeBinding(o.getString("key"), dir, output))
                }
                EdgeSwipeBindings(out)
            } catch (e: JSONException) {
                DEFAULTS
            }
        }
    }
}
