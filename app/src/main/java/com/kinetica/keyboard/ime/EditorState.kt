package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Per-field editor facts derived once in onStartInput. */
data class EditorState(
    val privateMode: Boolean,
    val multiline: Boolean,
    val actionId: Int,
    val capSentences: Boolean,
    val addressField: Boolean,
) {
    companion object {
        val DEFAULT = EditorState(
            privateMode = false, multiline = false,
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
                privateMode = password || noLearning,
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
