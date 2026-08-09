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
    private val onKeyTransition: (Int) -> Unit,
) {
    private val points = ArrayList<PathPoint>(128)
    private val contacts = ArrayList<KeyContact>(16)
    private val downX = downXPx / geometry.keyWidthPx
    private val downY = downYPx / geometry.keyWidthPx
    private var maxDispKw = 0f
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
    val isSwipeCommitted: Boolean get() = maxDispKw >= tapDispKw

    fun addPoint(xPx: Float, yPx: Float, t: Long) {
        val x = xPx / geometry.keyWidthPx
        val y = yPx / geometry.keyWidthPx
        val last = points[points.size - 1]
        val dxl = x - last.x
        val dyl = y - last.y
        val stepLen = sqrt(dxl * dxl + dyl * dyl)
        if (stepLen < 1e-4f && t == last.t) return
        points.add(PathPoint(x, y, t))
        arcLen += stepLen

        val dx = x - downX
        val dy = y - downY
        val disp = sqrt(dx * dx + dy * dy)
        if (disp > maxDispKw) maxDispKw = disp

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
     * as parked - deliberate: that speed produces no letters.
     */
    private fun trackDwell(idx: Int, x: Float, y: Float, t: Long) {
        val dx = x - runAnchorX
        val dy = y - runAnchorY
        if (sqrt(dx * dx + dy * dy) <= KineticaConstants.DWELL_RADIUS_KW) {
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
     * SHORTEST recorded dwell is dropped rather than the newest, so a gesture
     * with many small hesitations still surfaces its real boundaries; removal
     * keeps the list in time order, which is what path slicing consumes.
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

    fun finish(t: Long): InputToken {
        // The open run is deliberately NOT closed: a pause with no following leg
        // is not a boundary between anything, so a thumb resting before it lifts
        // must not segment the gesture.
        if (currentKey != -1) contacts.add(KeyContact(currentKey, currentEnter, t))
        val dur = t - downTime
        if (maxDispKw < tapDispKw) {
            // Spec-literal taps are <150 ms; a stationary dwell decodes exactly
            // like a zero-length swipe would, and the flag is the UI's
            // long-press hook.
            return TapToken(
                streamId, downCode, downX, downY,
                longPress = dur >= KineticaConstants.TAP_MAX_MS,
                tStart = downTime, tEnd = t,
            )
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        RESAMPLER.resample(points, resampled)
        return SwipeToken(
            streamId, ArrayList(points), resampled, ArrayList(contacts),
            arcLen, downTime, t, dwells = ArrayList(dwells),
        )
    }

    private companion object {
        // Resampling touches no DTW row state, so one shared instance is safe
        // here as long as streams are finished on a single (UI) thread.
        val RESAMPLER = DtwMatcher()
    }
}
