package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId

/**
 * Tracks up to two independent pointer streams and emits classified tokens.
 *
 * Pure Kotlin: the view layer unpacks MotionEvents (including historical
 * samples) into these primitive calls, keyed by pointerId only - pointer
 * *indices* shift on every pointer up/down and must never be stored.
 *
 * [maxPointers] defaults to 1 for safety; KineticaIME.onCreate sets 2, the
 * shipping dual-stream configuration. At 1 nothing else changes:
 * single-pointer tokens never overlap in time, so the dual-stream merge
 * machinery downstream degenerates to the identity.
 */
class GestureEngine(private val listener: Listener) {

    interface Listener {
        /** Main thread, fired at pointer lift. */
        fun onTokenFinalized(token: InputToken)

        /** The swipe path entered a new key: drives hue cycling and bursts. */
        fun onKeyTransition(streamId: StreamId, code: Int)

        /** Last active pointer lifted; a decode snapshot is now consistent. */
        fun onAllPointersUp()
    }

    var maxPointers: Int = 1

    private var geometry: KeyboardGeometry? = null
    private var tapDispKw = 0.5f
    private val streams = arrayOfNulls<GestureStream>(2)     // slot 0 = LEFT, 1 = RIGHT
    private val slotByPointer = IntArray(MAX_POINTER_ID) { -1 }

    fun setGeometry(g: KeyboardGeometry, tapMaxDispPx: Float) {
        geometry = g
        tapDispKw = tapMaxDispPx / g.keyWidthPx
    }

    private fun activeCount(): Int =
        (if (streams[0] != null) 1 else 0) + (if (streams[1] != null) 1 else 0)

    /**
     * Returns false when this pointer is not the engine's to track (not on a
     * letter key, over the pointer budget, or unknown id) - the caller routes
     * it to a special-key controller instead.
     */
    fun onPointerDown(pointerId: Int, xPx: Float, yPx: Float, t: Long): Boolean {
        val g = geometry ?: return false
        if (pointerId !in 0 until MAX_POINTER_ID) return false
        if (activeCount() >= maxPointers) return false
        val code = g.keyAt(xPx / g.keyWidthPx, yPx / g.keyWidthPx)
        if (code == -1) return false

        var slot = if (xPx < g.widthPx / 2f) 0 else 1
        if (streams[slot] != null) slot = 1 - slot
        if (streams[slot] != null) return false
        val streamId = if (slot == 0) StreamId.LEFT else StreamId.RIGHT
        streams[slot] = GestureStream(
            streamId, pointerId, g, tapDispKw, xPx, yPx, t, code,
        ) { keyCode -> listener.onKeyTransition(streamId, keyCode) }
        slotByPointer[pointerId] = slot
        return true
    }

    fun onPointerMove(pointerId: Int, xPx: Float, yPx: Float, t: Long) {
        val stream = streamFor(pointerId) ?: return
        stream.addPoint(xPx, yPx, t)
    }

    fun onPointerUp(pointerId: Int, xPx: Float, yPx: Float, t: Long) {
        val stream = streamFor(pointerId) ?: return
        stream.addPoint(xPx, yPx, t)
        val token = stream.finish(t)
        release(pointerId)
        listener.onTokenFinalized(token)
        if (activeCount() == 0) listener.onAllPointersUp()
    }

    fun cancelPointer(pointerId: Int) {
        if (streamFor(pointerId) != null) release(pointerId)
    }

    fun cancelAll() {
        streams[0] = null
        streams[1] = null
        java.util.Arrays.fill(slotByPointer, -1)
    }

    fun isTracking(pointerId: Int): Boolean = streamFor(pointerId) != null

    /** Whether the pointer has committed to a swipe (for trail rendering). */
    fun isSwipeCommitted(pointerId: Int): Boolean =
        streamFor(pointerId)?.isSwipeCommitted == true

    fun streamIdOf(pointerId: Int): StreamId? = streamFor(pointerId)?.streamId

    fun hasActivePointers(): Boolean = activeCount() > 0

    private fun streamFor(pointerId: Int): GestureStream? {
        if (pointerId !in 0 until MAX_POINTER_ID) return null
        val slot = slotByPointer[pointerId]
        if (slot == -1) return null
        val s = streams[slot]
        return if (s != null && s.pointerId == pointerId) s else null
    }

    private fun release(pointerId: Int) {
        val slot = slotByPointer[pointerId]
        if (slot != -1) {
            streams[slot] = null
            slotByPointer[pointerId] = -1
        }
    }

    private companion object {
        const val MAX_POINTER_ID = 64
    }
}
