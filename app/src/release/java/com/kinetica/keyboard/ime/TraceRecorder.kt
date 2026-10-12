package com.kinetica.keyboard.ime

import android.content.Context

/**
 * Release build: decode tracing does not exist.
 *
 * The debug build's recorder writes every decoded gesture to a file, so it lives in the debug
 * source set and this stub replaces it. A released APK contains no code at all that can write what
 * was typed to disk, not even disabled code, and no crash handler.
 */
object TraceRecorder {
    /** Never read in a release build; the service sets it for the debug build's crash lines. */
    @Volatile
    var crashState: (() -> String)? = null

    /** No trace sink is installed in a release build. */
    fun install(context: Context) = Unit
}
