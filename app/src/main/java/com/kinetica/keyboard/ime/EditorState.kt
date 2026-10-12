package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What a field holds, as far as its keys go: an email address or a web address wants a key the
 * comma can give up, and a number field may want the number pad.
 */
enum class FieldKind {
    TEXT, EMAIL, URL, NUMERIC;

    /** The character the comma key types in this kind of field, or null for the comma. */
    val commaChar: String?
        get() = when (this) {
            EMAIL -> "@"
            URL -> "/"
            else -> null
        }
}

/**
 * Per-field editor facts derived once in onStartInput.
 *
 * [privateMode] and [noLearning] are separate because the two requests are: a password
 * field wants nothing offered and nothing kept, while a no-learning field wants an ordinary
 * keyboard that forgets. [teachesNothing] is the guard for anything that persists.
 */
data class EditorState(
    val privateMode: Boolean,
    val noLearning: Boolean,
    val multiline: Boolean,
    val actionId: Int,
    val capSentences: Boolean,
    val addressField: Boolean,
    /** IME_FLAG_NO_ENTER_ACTION: the app wants enter to write a newline, whatever its action. */
    val noEnterAction: Boolean = false,
    /** The app's own name for its enter action, or null. */
    val actionLabel: String? = null,
    /** The action id that goes with [actionLabel]. */
    val customActionId: Int = 0,
    val fieldKind: FieldKind = FieldKind.TEXT,
) {
    /** True when nothing about this field may be persisted, for either reason. */
    val teachesNothing: Boolean get() = privateMode || noLearning

    /**
     * True when a correction may be offered after a commit.
     *
     * [privateMode] alone: the strip records nothing, it names the word on screen and the
     * alternatives it beat, and every learning call behind a pick refuses the field itself.
     * Gating it on [teachesNothing] lost the strip in every app that sets
     * IME_FLAG_NO_PERSONALIZED_LEARNING on an ordinary field. A password field still gets
     * nothing, because the strip would put the password back on screen.
     */
    val offersCorrections: Boolean get() = !privateMode

    companion object {
        /**
         * The private option Kinetica's own settings put on a field that holds one token, an
         * expansion trigger. Read as [addressField], the same promise: no automatic space is
         * right there. Other keyboards ignore it.
         */
        const val ONE_TOKEN_OPTION = "com.kinetica.keyboard.oneToken"

        internal fun asksForOneToken(privateImeOptions: String?): Boolean =
            privateImeOptions?.split(',')?.any { it.trim() == ONE_TOKEN_OPTION } == true

        val DEFAULT = EditorState(
            privateMode = false, noLearning = false, multiline = false,
            actionId = EditorInfo.IME_ACTION_NONE, capSentences = false,
            addressField = false,
        )

        fun from(info: EditorInfo?): EditorState {
            if (info == null) return DEFAULT
            val inputType = info.inputType
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION

            val password = (
                cls == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    )
                ) || (
                cls == InputType.TYPE_CLASS_NUMBER &&
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
            // IME_FLAG_NO_PERSONALIZED_LEARNING asks only that nothing be learned. Apps such as
            // DuckDuckGo and Molly set it on ordinary text fields; folded into privateMode it
            // cost them suggestions, autocorrect and autospace.
            val noLearning =
                info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

            // A field whose whole value is one token: an email address or a URL. An automatic
            // space there lands before the '@', deleting it re-arms the timer, and the address
            // cannot be finished. Read once here, not at each arming site.
            val address = cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI
                ) || asksForOneToken(info.privateImeOptions)

            val kind = when {
                cls == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                    ) -> FieldKind.EMAIL
                cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                !password && (
                    cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                        cls == InputType.TYPE_CLASS_DATETIME
                    ) -> FieldKind.NUMERIC
                else -> FieldKind.TEXT
            }

            return EditorState(
                privateMode = password,
                noLearning = noLearning,
                multiline = cls == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                actionId = info.imeOptions and EditorInfo.IME_MASK_ACTION,
                // Never in an address, whatever the app asks: `Name@` is no address.
                capSentences = cls == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 &&
                    kind != FieldKind.EMAIL && kind != FieldKind.URL,
                addressField = address,
                noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0,
                actionLabel = info.actionLabel?.toString()?.takeIf { it.isNotBlank() },
                customActionId = info.actionId,
                fieldKind = kind,
            )
        }
    }
}
