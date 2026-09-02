package com.kinetica.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fields take an automatic space.
 *
 * `EditorInfo` is a plain data holder with no framework behind it, so the derivation is
 * reachable from the JVM even though the service around it is not - the same reason
 * [autospacesTappedWord] is a free function.
 */
class EditorStateTest {

    private fun stateFor(inputType: Int): EditorState =
        EditorState.from(EditorInfo().also { it.inputType = inputType })

    @Test
    fun anEmailFieldIsAnAddress() {
        assertTrue(
            stateFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            ).addressField,
        )
    }

    @Test
    fun aWebEmailFieldIsAnAddress() {
        // The variation browsers report, which is not the same constant.
        assertTrue(
            stateFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            ).addressField,
        )
    }

    @Test
    fun aUrlFieldIsAnAddress() {
        assertTrue(
            stateFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).addressField,
        )
    }

    @Test
    fun ordinaryProseIsNot() {
        assertFalse(
            stateFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            ).addressField,
        )
    }

    @Test
    fun anEmailSubjectIsNot() {
        // Prose that happens to live in a mail client. Only the address line is a
        // single token.
        assertFalse(
            stateFor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            ).addressField,
        )
    }

    @Test
    fun aFieldWithNoEditorInfoIsNot() {
        assertFalse(EditorState.from(null).addressField)
        assertFalse(EditorState.DEFAULT.addressField)
    }
}
