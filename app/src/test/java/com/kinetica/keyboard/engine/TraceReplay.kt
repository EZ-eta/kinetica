package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Rebuilds a token buffer from a `decode in` line of a device capture.
 *
 * This is what printing key contacts was for. A captured buffer used to be
 * unreconstructible - the trace held token intervals and nothing else - so a real
 * failure could only ever be approximated by a hand-written fixture. With contacts
 * and their times the buffer comes back exactly: same streams, same intervals, same
 * keys, same order.
 *
 * What does NOT come back is the path between contacts. The trace records which keys
 * a thumb crossed and when, not where it went, so the polyline here runs through the
 * contact key centres. That is cleaner than a real thumb and it compresses the
 * distance between rival words, which is the standing caveat on every reconstruction
 * in this project: **a replayed buffer is a reachability fixture, not a ranking one.**
 * Whether a word can be spelled at all is preserved; which of two words wins is not.
 *
 * One half of that caveat is now measured and repaired. The polyline was also 27% shorter
 * than the real stroke, and arc length is not a ranking quantity at all: it is what
 * decides how many letters a piece may spell. A capture carrying `arc=` therefore has its
 * recorded arc injected, so `minLetters` and both length bands see the travel the thumb
 * actually made. What stays a reconstruction is the shape.
 */
object TraceReplay {

    private val TAP = Regex("""tap\[([a-z]),(LEFT|RIGHT),t=(\d+)]""")
    // The whole bracket, not just the contacts, so the fields that follow `keys=` can be
    // read. Stops at the first `]`, and a swipe label contains none.
    private val SWIPE = Regex("""swipe\[(LEFT|RIGHT),t=(\d+)\.\.(\d+),keys=([^\]]*)\]""")
    private val CONTACT = Regex("""([a-z])@(-?\d+)-(-?\d+)""")
    private val ARC = Regex("""arc=([0-9.]+)""")

    fun tokens(line: String, g: KeyboardGeometry): List<InputToken> {
        val out = ArrayList<InputToken>(4)
        for (m in TAP.findAll(line)) {
            val code = m.groupValues[1][0] - 'a'
            val t = m.groupValues[3].toLong()
            out.add(
                TapToken(
                    StreamId.valueOf(m.groupValues[2]), code,
                    g.centerX(code), g.centerY(code), false, t, t + 60,
                ),
            )
        }
        for (m in SWIPE.findAll(line)) {
            out.add(swipe(m, g))
        }
        return out.sortedBy { it.tStart }
    }

    private fun swipe(m: MatchResult, g: KeyboardGeometry): SwipeToken {
        val stream = StreamId.valueOf(m.groupValues[1])
        val t0 = m.groupValues[2].toLong()
        val t1 = m.groupValues[3].toLong()
        val contacts = ArrayList<KeyContact>()
        for (c in CONTACT.findAll(m.groupValues[4])) {
            val code = c.groupValues[1][0] - 'a'
            contacts.add(KeyContact(code, t0 + c.groupValues[2].toLong(), t0 + c.groupValues[3].toLong()))
        }
        // Where a finger is when a contact BEGINS is the edge between the key it is
        // leaving and the key it is entering; where it is when the last contact ends
        // is that key's centre, because that is where the gesture stopped. Vertices
        // therefore sit on key boundaries at contact-entry times, and the path
        // interpolates between them.
        //
        // The first attempt here parked a run of samples on each key centre for the
        // whole contact. That is not what a moving finger does, and it gave any piece
        // cut inside a contact an arc of zero, which the split's own minimum-arc rule
        // rejects - so the merge looked broken when the fixture was.
        val path = ArrayList<PathPoint>(64)
        val vx = ArrayList<Float>(); val vy = ArrayList<Float>(); val vt = ArrayList<Long>()
        for ((i, c) in contacts.withIndex()) {
            val cx = g.centerX(c.code); val cy = g.centerY(c.code)
            if (i == 0) {
                vx.add(cx); vy.add(cy); vt.add(c.tEnter)
            } else {
                val p = contacts[i - 1]
                vx.add((g.centerX(p.code) + cx) / 2f)
                vy.add((g.centerY(p.code) + cy) / 2f)
                vt.add(c.tEnter)
            }
        }
        contacts.lastOrNull()?.let {
            vx.add(g.centerX(it.code)); vy.add(g.centerY(it.code)); vt.add(maxOf(it.tExit, vt.last() + 1))
        }
        if (vx.size == 1) {
            for (k in 0 until 8) path.add(PathPoint(vx[0], vy[0], t0 + (t1 - t0) * k / 8))
        } else {
            val perSeg = 6
            for (i in 0 until vx.size - 1) {
                for (k in 0 until perSeg) {
                    val f = k / perSeg.toFloat()
                    path.add(
                        PathPoint(
                            vx[i] + f * (vx[i + 1] - vx[i]),
                            vy[i] + f * (vy[i + 1] - vy[i]),
                            vt[i] + ((vt[i + 1] - vt[i]) * k / perSeg),
                        ),
                    )
                }
            }
            if (vx.isNotEmpty()) path.add(PathPoint(vx.last(), vy.last(), vt.last()))
        }
        if (path.isEmpty()) path.add(PathPoint(0f, 0f, t0))
        var arc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        // The RECORDED arc wins when the capture carries one, because the polyline's own
        // length is the single measurable thing wrong with this reconstruction: against
        // 1 204 real swipes it runs 27% short at the median (ratio 0.73, p10 0.48), and
        // for 141 of 971 the real arc demands two letters of a piece where the polyline
        // demands one. Arc is what `Matcher.buildSegment` reads for minLetters, maxLetters
        // and both length bands, so every gate measurement this project has published sat
        // on paths a quarter too short.
        //
        // Shape from the reconstruction, travel from the measurement. The resampled path
        // is untouched, so the pass and endpoint geometry are exactly what they were and
        // DTW scores the same polyline as before.
        //
        // Residual, stated rather than hidden: MergeAlternatives recomputes a cut piece's
        // arc from the sliced path, so split pieces keep the short arc. Whole gestures are
        // exact, which is where 81% of the minLetters decisions are made.
        val recorded = ARC.find(m.groupValues[4])?.groupValues?.get(1)?.toFloatOrNull()
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        return SwipeToken(stream, path, resampled, contacts, recorded ?: arc, t0, t1)
    }
}
