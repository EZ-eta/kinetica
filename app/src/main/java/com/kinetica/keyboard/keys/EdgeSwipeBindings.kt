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
