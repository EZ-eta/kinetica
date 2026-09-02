package com.kinetica.keyboard.ime

import android.content.Context

/**
 * Release build: decode tracing does not exist.
 *
 * The developer build carries a recorder that writes every decoded gesture to a file.
 * That is the right tool for engine work and the wrong thing to ship, so it lives in
 * the debug source set and this stub takes its place here. A released APK therefore
 * contains no code that can write what was typed to disk - not disabled code, none -
 * which is the only version of that claim worth making about a keyboard.
 */
object TraceRecorder {
    /** No trace sink is installed in a release build. */
    fun install(context: Context) = Unit
}
