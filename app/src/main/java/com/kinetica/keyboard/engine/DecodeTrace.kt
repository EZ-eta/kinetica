package com.kinetica.keyboard.engine

/**
 * Optional, default-off tracing of the decode path (token buffer, merge-split
 * decisions, resulting candidates). Exists because the resume-after-interruption
 * bug class is reproducible only as a real two-thumb
 * device gesture: the JVM golden suite fixes the mechanism, but the *actual*
 * on-device timing/geometry can only be captured live. With a device attached
 * you set a sink (KineticaIME wires it to Logcat in debuggable
 * builds) and reads the real token buffer, split points and candidate list via
 * `adb logcat -s KineticaTrace`.
 *
 * Engine-pure (no Android imports) so the engine's JVM-testability is preserved;
 * the sink is a plain function reference. The message is built lazily so a
 * disabled trace costs one null check per call site, nothing more. The decode
 * thread writes; the sink implementation must be thread-safe (Logcat is).
 */
object DecodeTrace {

    @Volatile
    var sink: ((String) -> Unit)? = null

    val enabled: Boolean get() = sink != null

    inline fun log(message: () -> String) {
        sink?.invoke(message())
    }
}
