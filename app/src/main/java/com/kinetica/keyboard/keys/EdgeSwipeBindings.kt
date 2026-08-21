package com.kinetica.keyboard.keys

import com.kinetica.keyboard.layout.KeyboardLayout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One user-configurable edge-swipe shortcut: key + direction -> action. */
data class EdgeSwipeBinding(
    val keyId: String,
    val direction: Direction,
    /** Text to insert, or [EdgeSwipeBindings.ACTION_EMOJI] for the picker. */
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

        // Letter rows by normalized Key.y (top 0.00, home 0.25, bottom 0.50 in
        // the bundled layouts). Half-row bands tolerate any layout rounding
        // without catching the home row or the space/enter row (0.75).
        private const val TOP_ROW_Y_MAX = 0.1f
        private const val BOTTOM_ROW_Y_MIN = 0.4f
        private const val BOTTOM_ROW_Y_MAX = 0.6f

        /**
         * Which built-in gesture a binding on [keyId] in [direction] would
         * shadow, or null when it is safe. Pure so the settings screen and the
         * tests agree on the rule rather than each spelling it out.
         *
         * The three collisions are all real and all silent today. The shipped
         * defaults avoid every one of them, so this only bites someone who
         * customises - which is exactly the person with no way to find out.
         *
         * Returns an identifier for the shadowed gesture, not a message: the
         * strings live with the screen that shows them.
         */
        fun shadowedGesture(keyId: String, direction: EdgeSwipeBinding.Direction): String? {
            val horizontal = direction == EdgeSwipeBinding.Direction.LEFT ||
                direction == EdgeSwipeBinding.Direction.RIGHT
            return when {
                // The spacebar slides to move the cursor; a horizontal binding on
                // it competes with every cursor move.
                keyId == "space" && horizontal -> SHADOWS_CURSOR_SLIDE
                // Backspace slides left to stage a deletion, which is the gesture
                // most likely to be triggered by accident.
                keyId == "backspace" && horizontal -> SHADOWS_STAGED_DELETE
                // ?123 slides sideways to reach the numpad and back. The key's
                // id is "mode"; its TYPE is mode_symbols.
                keyId == "mode" && horizontal -> SHADOWS_LAYER_SLIDE
                // Enter slides left for its alternates popup. UP is deliberately
                // NOT flagged: the shipped default binds enter-up to "?", which is
                // the popup's own primary, so the two are the same answer rather
                // than a collision.
                keyId == "enter" && horizontal -> SHADOWS_ENTER_POPUP
                // A horizontal binding on a letter key competes with short typing
                // swipes, which is how the engine reads a two-letter word.
                keyId.length == 1 && keyId[0] in 'a'..'z' && horizontal ->
                    SHADOWS_TYPING_SWIPE
                else -> null
            }
        }

        const val SHADOWS_CURSOR_SLIDE = "cursor_slide"
        const val SHADOWS_STAGED_DELETE = "staged_delete"
        const val SHADOWS_LAYER_SLIDE = "layer_slide"
        const val SHADOWS_ENTER_POPUP = "enter_popup"
        const val SHADOWS_TYPING_SWIPE = "typing_swipe"

        /**
         * Synthesizes the implicit alternate-swipe layer from
         * [layout] and layers [explicit] on top so user/built-in bindings win:
         * a swipe UP on a top-row letter key inserts that key's first
         * non-letter alternate (its digit); a swipe DOWN on a bottom-row letter
         * key inserts its first non-letter alternate (its symbol). Home-row and
         * non-letter keys get nothing.
         *
         * "First non-letter alternate" is the same predicate as
         * [LayoutMutations.withNumberPriority][com.kinetica.keyboard.layout.LayoutMutations.withNumberPriority]:
         * a vowel that lists accents before its digit (e = [è,é,ê,ë,ē,3]) still
         * yields the digit, and qwerty_es "n" = [ñ,!,¡] yields "!".
         *
         * Explicit precedence is achieved by ordering, not a second lookup: the
         * implicit entries come first in the list, so an explicit
         * "keyId/direction" appended after overwrites it in [byKeyAndDir]
         * (the built-in v-down "," / b-down "." / x-down emoji keep their keys).
         * The result is a runtime-only set; it is never serialized, so the
         * implicit rows never reach the persisted binding preference.
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
                val symbol = k.alternates.firstOrNull { it.firstOrNull()?.isLetter() != true }
                    ?: continue
                implicit.add(EdgeSwipeBinding(k.id, direction, symbol))
            }
            return EdgeSwipeBindings(implicit + explicit.bindings)
        }

        /** The original five built-in shortcuts. */
        val DEFAULTS = EdgeSwipeBindings(
            listOf(
                EdgeSwipeBinding("backspace", EdgeSwipeBinding.Direction.UP, "!"),
                EdgeSwipeBinding("enter", EdgeSwipeBinding.Direction.UP, "?"),
                EdgeSwipeBinding("v", EdgeSwipeBinding.Direction.DOWN, ","),
                EdgeSwipeBinding("b", EdgeSwipeBinding.Direction.DOWN, "."),
                EdgeSwipeBinding("x", EdgeSwipeBinding.Direction.DOWN, ACTION_EMOJI),
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
