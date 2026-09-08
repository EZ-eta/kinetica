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

    /** A field carrying an imeOptions flag rather than an inputType variation. */
    private fun stateForOptions(inputType: Int, imeOptions: Int): EditorState =
        EditorState.from(
            EditorInfo().also {
                it.inputType = inputType
                it.imeOptions = imeOptions
            },
        )

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

    // ------------------------------------------------- private versus no-learning
    //
    // Reported by a user: the keyboard looked stuck in password mode in DuckDuckGo and
    // Molly. Both set IME_FLAG_NO_PERSONALIZED_LEARNING on ordinary text fields, and that
    // flag used to be folded into privateMode, which switches off the whole suggestion
    // pipeline. The flag asks the keyboard to forget, not to stop working.

    @Test
    fun aNoLearningFieldStillOffersSuggestions() {
        val s = stateForOptions(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertFalse(s.privateMode)
    }

    @Test
    fun aNoLearningFieldDoesNotLearn() {
        val s = stateForOptions(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertTrue(s.noLearning)
        assertTrue(s.teachesNothing)
    }

    @Test
    fun aPasswordFieldIsBothPrivateAndTeachesNothing() {
        val s = stateFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(s.privateMode)
        assertTrue(s.teachesNothing)
    }

    @Test
    fun aVisiblePasswordFieldIsStillPrivate() {
        // The variation a "show password" toggle switches to. Visible to the user is not
        // the same as safe to keep.
        val s = stateFor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        assertTrue(s.privateMode)
    }

    @Test
    fun aNumericPinIsPrivate() {
        val s = stateFor(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        assertTrue(s.privateMode)
    }

    @Test
    fun ordinaryProseIsNeither() {
        val s = stateFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertFalse(s.privateMode)
        assertFalse(s.noLearning)
        assertFalse(s.teachesNothing)
    }

    @Test
    fun aNoLearningPasswordFieldIsBoth() {
        // Nothing stops an app setting both, and the password half must win the display
        // decisions.
        val s = stateForOptions(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertTrue(s.privateMode)
        assertTrue(s.noLearning)
    }
}
