package com.kinetica.keyboard.engine

/**
 * Optional, default-off tracing of the decode path: token buffer, merge-split decisions,
 * candidates. Two-thumb timing and geometry can only be captured on a device, so the DEV
 * build's TraceRecorder points [sink] at Logcat and a file (`adb logcat -s KineticaTrace`).
 *
 * Engine-pure, so the sink is a plain function reference. Messages are built lazily, so a disabled
 * trace costs one null check per call site. The decode thread writes; the sink must be
 * thread-safe (Logcat is).
 */
object DecodeTrace {

    @Volatile
    var sink: ((String) -> Unit)? = null

    val enabled: Boolean get() = sink != null

    /** This thread's held lines while it decodes beside another thread, else null. */
    @PublishedApi
    internal val heldLines = ThreadLocal<ArrayList<String>?>()

    inline fun log(message: () -> String) {
        val s = sink ?: return
        val held = heldLines.get()
        if (held != null) held.add(message()) else s(message())
    }

    /**
     * Runs [block] with this thread's lines held, not written, and returns them with its result.
     * A second language decodes on its own thread; writing its lines after the first language's
     * keeps one block per decode for the corpus tools.
     */
    fun <T> holding(block: () -> T): Pair<T, List<String>> {
        if (sink == null) return block() to emptyList()
        val held = ArrayList<String>()
        heldLines.set(held)
        try {
            return block() to held
        } finally {
            heldLines.set(null)
        }
    }

    /** Writes lines [holding] kept back, in order. */
    fun write(lines: List<String>) {
        val s = sink ?: return
        for (l in lines) s(l)
    }
}
