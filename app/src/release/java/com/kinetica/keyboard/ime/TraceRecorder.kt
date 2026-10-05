package com.kinetica.keyboard.ime

import android.content.Context

/**
 * Release build: decode tracing does not exist.
 *
 * The developer build's recorder writes every decoded gesture to a file, so it lives in the debug
 * source set and this stub replaces it. A released APK contains no code at all that can write what
 * was typed to disk, not even disabled code.
 */
object TraceRecorder {
    /** No trace sink is installed in a release build. */
    fun install(context: Context) = Unit
}
