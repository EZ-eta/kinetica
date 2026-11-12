package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Accumulates one pointer's path (kw coordinates) and classifies it on lift.
 *
 * Key contacts use sticky-bounds hysteresis: the current key keeps ownership
 * until the pointer exits its rect inflated by STICKY_INFLATE_KW, so a finger
 * wobbling on the G/H border cannot emit G,H,G,H spam. Consecutive duplicates
 * are structurally impossible in the contact list.
 */
class GestureStream(
    val streamId: StreamId,
    val pointerId: Int,
    private val geometry: KeyboardGeometry,
    private val tapDispKw: Float,
    downXPx: Float,
    downYPx: Float,
    val downTime: Long,
    val downCode: Int,
    /**
     * The rest of a stroke cut at the apostrophe key ([cut]): no key seeds it, its first contact
     * is the first letter it reaches, and its swipe is softStart (a lead-in from the key, not a
     * first letter).
     */
    val resumed: Boolean = false,
    /** For a [resumed] stream: whether [finish] drops the travel from the key ([LeadIn]). */
    private val trimsLeadIn: Boolean = true,
    private val onKeyTransition: (Int) -> Unit,
) {
    private val points = ArrayList<PathPoint>(128)
    private val contacts = ArrayList<KeyContact>(16)
    private val downX = downXPx / geometry.keyWidthPx
    private val downY = downYPx / geometry.keyWidthPx
    private val tapDispSqKw = tapDispKw * tapDispKw
    private var maxDispSqKw = 0f
    private var currentKey = downCode
    private var currentEnter = downTime
    private var arcLen = 0f

    // Current candidate stationary run: the samples since the pointer last
    // travelled more than DWELL_RADIUS_KW from where the run started.
    private val dwells = ArrayList<Dwell>(KineticaConstants.MAX_DWELL_SEGMENTS)
    private var runAnchorIdx = 0
    private var runAnchorX = downX
    private var runAnchorY = downY
    private var runAnchorT = downTime
    private var runLastIdx = 0
    private var runLastT = downTime

    init {
        points.add(PathPoint(downX, downY, downTime))
    }

    /** True once displacement rules out a tap (the UI uses this to start the trail). */
    val isSwipeCommitted: Boolean get() = maxDispSqKw >= tapDispSqKw

    /**
     * Key contacts so far, including the one under the finger right now.
     *
     * [contacts] is appended only when the pointer leaves a key, so the current one is not in
     * it yet; for an engine-owned stream `currentKey` is never -1, since it is seeded from the
     * down key and a transition never assigns -1. Hence the plus one. A [resumed] stream is -1
     * until it reaches a letter, and counts 0 till then.
     *
     * Counts intervals, not distinct keys: a path that returns to an earlier key counts it
     * twice. The caller asks whether the pointer has been travelling, not which keys it saw.
     */
    val contactCount: Int get() = contacts.size + if (currentKey != -1) 1 else 0

    /** Where the pointer is now, in kw. */
    val lastX: Float get() = points[points.size - 1].x
    val lastY: Float get() = points[points.size - 1].y

    fun addPoint(xPx: Float, yPx: Float, t: Long) {
        val x = xPx / geometry.keyWidthPx
        val y = yPx / geometry.keyWidthPx
        val last = points[points.size - 1]
        val dxl = x - last.x
        val dyl = y - last.y
        val stepSq = dxl * dxl + dyl * dyl
        if (stepSq < MIN_STEP_SQ_KW && t == last.t) return
        if (stepSq > TELEPORT_STEP_SQ_KW) {
            DecodeTrace.log { "pointer jump stream=$streamId step=${"%.2f".format(sqrt(stepSq))}kw dt=${t - last.t}ms" }
        }
        points.add(PathPoint(x, y, t))
        arcLen += sqrt(stepSq)

        // Tap classification and dwell membership only need radius comparisons.
        // Keeping both sides squared removes two square roots from every raw
        // touch sample on the UI thread; boundary behavior is locked by tests.
        val dx = x - downX
        val dy = y - downY
        val dispSq = dx * dx + dy * dy
        if (dispSq > maxDispSqKw) maxDispSqKw = dispSq

        // Before the key-contact early-outs below: a dwell must be seen on every
        // sample, including the ones that change no key.
        trackDwell(points.size - 1, x, y, t)

        if (currentKey != -1 &&
            geometry.insideInflated(x, y, currentKey, KineticaConstants.STICKY_INFLATE_KW)
        ) return
        val newKey = geometry.keyAt(x, y)
        if (newKey == -1 || newKey == currentKey) return
        if (currentKey != -1) contacts.add(KeyContact(currentKey, currentEnter, t))
        currentKey = newKey
        currentEnter = t
        onKeyTransition(newKey)
    }

    /**
     * Extends the current stationary run, or closes it and re-anchors here.
     * The radius is measured from the run's first sample, so a pointer creeping
     * slower than DWELL_RADIUS_KW / DWELL_MIN_MS never escapes a run and counts
     * as parked; that speed produces no letters.
     */
    private fun trackDwell(idx: Int, x: Float, y: Float, t: Long) {
        val dx = x - runAnchorX
        val dy = y - runAnchorY
        if (dx * dx + dy * dy <= DWELL_RADIUS_SQ_KW) {
            runLastIdx = idx
            runLastT = t
            return
        }
        closeRun()
        runAnchorIdx = idx
        runAnchorX = x
        runAnchorY = y
        runAnchorT = t
        runLastIdx = idx
        runLastT = t
    }

    /**
     * Records the open run as a dwell if it lasted long enough. On overflow the
     * shortest recorded dwell is dropped, not the newest, so a gesture with many
     * small hesitations still surfaces its real boundaries; removal keeps the
     * list in time order for path slicing.
     */
    private fun closeRun() {
        if (runLastT - runAnchorT < KineticaConstants.DWELL_MIN_MS) return
        dwells.add(Dwell(runAnchorIdx, runLastIdx, runAnchorT, runLastT))
        if (dwells.size < KineticaConstants.MAX_DWELL_SEGMENTS) return
        var shortest = 0
        for (i in 1 until dwells.size) {
            if (dwells[i].tExit - dwells[i].tEnter < dwells[shortest].tExit - dwells[shortest].tEnter) {
                shortest = i
            }
        }
        dwells.removeAt(shortest)
    }

    /**
     * The token for this stroke, lifted at [t]. [softEnd]: the stroke was cut, so its end is run-out
     * onto the apostrophe key, not its last letter. A [resumed] stream with no contact has no token;
     * the caller asks [contactCount] first.
     */
    fun finish(t: Long, softEnd: Boolean = false): InputToken {
        // The open run is not closed: a pause with no following leg is not a boundary, so a
        // thumb resting before it lifts must not segment the gesture.
        if (currentKey != -1) contacts.add(KeyContact(currentKey, currentEnter, t))
        val dur = t - downTime
        if (maxDispSqKw < tapDispSqKw) {
            // Spec-literal taps are <150 ms; a stationary dwell decodes like a
            // zero-length swipe would, and the flag is the UI's long-press hook.
            // A resumed stroke has no down key: its tap is the letter it reached.
            val code = if (resumed) contacts.firstOrNull()?.code ?: -1 else downCode
            return TapToken(
                streamId, code, downX, downY,
                longPress = dur >= KineticaConstants.TAP_MAX_MS,
                tStart = downTime, tEnd = t,
            )
        }
        if (resumed && trimsLeadIn) {
            // The travel from the apostrophe key spells nothing: the word starts where it turns.
            val start = LeadIn.end(points, dwells)
            if (start > 0 && start < points.size - 1) {
                val kw = geometry.keyWidthPx
                val word = GestureStream(
                    streamId, pointerId, geometry, tapDispKw, points[start].x * kw, points[start].y * kw,
                    points[start].t, -1, resumed = true, trimsLeadIn = false,
                ) {}
                for (i in start + 1 until points.size) word.addPoint(points[i].x * kw, points[i].y * kw, points[i].t)
                if (word.contactCount > 0) return word.finish(t, softEnd)
            }
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        RESAMPLER.resample(points, resampled)
        return SwipeToken(
            streamId, ArrayList(points), resampled, ArrayList(contacts),
            arcLen, downTime, t, softStart = resumed, softEnd = softEnd, dwells = ArrayList(dwells),
        )
    }

    /** A stroke cut at the apostrophe key: the token before it, and the stream that goes on after. */
    class Cut(val head: InputToken, val tail: GestureStream)

    /**
     * Cuts this stroke where it entered the apostrophe key ([tEnter], the first sample inside) and left
     * it ([tExit], the first sample outside). Both
     * halves are this stroke's own points replayed into fresh streams, so each is classified as a
     * lift would classify it. The head is finished softEnd; the tail goes on as a [resumed] stream.
     * Null when either half has no point, or the head reached no letter.
     */
    fun cut(tEnter: Long, tExit: Long): Cut? {
        val headPts = points.filter { it.t < tEnter }
        val tailPts = points.filter { it.t >= tExit }
        if (headPts.isEmpty() || tailPts.isEmpty()) return null
        val kw = geometry.keyWidthPx
        val head = GestureStream(
            streamId, pointerId, geometry, tapDispKw, headPts[0].x * kw, headPts[0].y * kw, downTime, downCode, resumed,
        ) {}
        for (i in 1 until headPts.size) head.addPoint(headPts[i].x * kw, headPts[i].y * kw, headPts[i].t)
        if (head.contactCount == 0) return null
        val tail = GestureStream(
            streamId, pointerId, geometry, tapDispKw, tailPts[0].x * kw, tailPts[0].y * kw, tailPts[0].t, -1,
            resumed = true, onKeyTransition = onKeyTransition,
        )
        for (i in 1 until tailPts.size) tail.addPoint(tailPts[i].x * kw, tailPts[i].y * kw, tailPts[i].t)
        return Cut(head.finish(tEnter, softEnd = true), tail)
    }

    private companion object {
        private const val TELEPORT_STEP_SQ_KW =
            KineticaConstants.TELEPORT_STEP_KW * KineticaConstants.TELEPORT_STEP_KW

        const val MIN_STEP_SQ_KW = 1e-8f
        val DWELL_RADIUS_SQ_KW =
            KineticaConstants.DWELL_RADIUS_KW * KineticaConstants.DWELL_RADIUS_KW

        // Resampling touches no DTW row state, so one shared instance is safe
        // here as long as streams are finished on a single (UI) thread.
        val RESAMPLER = DtwMatcher()
    }
}
