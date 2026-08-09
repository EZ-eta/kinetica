package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Per-field editor facts derived once in onStartInput. */
data class EditorState(
    val privateMode: Boolean,
    val multiline: Boolean,
    val actionId: Int,
    val capSentences: Boolean,
) {
    companion object {
        val DEFAULT = EditorState(
            privateMode = false, multiline = false,
            actionId = EditorInfo.IME_ACTION_NONE, capSentences = false,
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

            return EditorState(
                privateMode = password || noLearning,
                multiline = cls == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                actionId = info.imeOptions and EditorInfo.IME_MASK_ACTION,
                capSentences = cls == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0,
            )
        }
    }
}
