package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

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
) {
    /** True when nothing about this field may be persisted, for either reason. */
    val teachesNothing: Boolean get() = privateMode || noLearning

    companion object {
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
            // IME_FLAG_NO_PERSONALIZED_LEARNING asks the keyboard not to LEARN from this
            // field. It is not a password flag, and privacy-focused apps set it on ordinary
            // text fields: DuckDuckGo and Molly both do, and folding it into privateMode
            // left them with no suggestions, no autocorrect and no autospace at all.
            val noLearning =
                info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

            // A field whose whole value is one unbroken token: an email address or a
            // URL. Every automatic space is wrong there, and the cost of one is not a
            // stray character but an unusable field - the space lands between the local
            // part and the '@', deleting it re-arms the timer, and the address cannot be
            // finished. Read once here rather than at each arming site.
            val address = cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI
                )

            return EditorState(
                privateMode = password,
                noLearning = noLearning,
                multiline = cls == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                actionId = info.imeOptions and EditorInfo.IME_MASK_ACTION,
                capSentences = cls == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0,
                addressField = address,
            )
        }
    }
}
