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
 * Rebuilds a token buffer from a `decode in` line of a device capture.
 *
 * Contacts and their times bring the buffer back exactly: same streams, intervals,
 * keys and order. The path between contacts does not come back: the trace records
 * which keys a thumb crossed and when, not where it went, so the polyline runs
 * through the contact key centres. That is cleaner than a real thumb and compresses
 * the distance between rival words, so a replayed buffer is a reachability fixture,
 * not a ranking one: whether a word can be spelled is preserved, which of two words
 * wins is not.
 *
 * The polyline also runs 27% shorter than the real stroke, and arc decides how many
 * letters a piece may spell, so a capture carrying `arc=` has its recorded arc
 * injected. What stays a reconstruction is the shape.
 */
object TraceReplay {

    private val TAP = Regex("""tap\[([a-z]),(LEFT|RIGHT),t=(\d+)]""")
    // The whole bracket, not just the contacts, so the fields that follow `keys=` can be
    // read. Stops at the first `]`, and a swipe label contains none.
    private val SWIPE = Regex("""swipe\[(LEFT|RIGHT),t=(\d+)\.\.(\d+),keys=([^\]]*)\]""")
    private val CONTACT = Regex("""([a-z])@(-?\d+)-(-?\d+)""")
    private val ARC = Regex("""arc=([0-9.]+)""")
    private val DWELL = Regex("""(\d+)-(\d+)/[0-9.]+kw/\d+n""")

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
        // When a contact begins the finger is on the edge between the key it leaves and
        // the key it enters; when the last contact ends it is on that key's centre,
        // where the gesture stopped. So vertices sit on key boundaries at contact-entry
        // times and the path interpolates between them. Parking samples on each key
        // centre would give a piece cut inside a contact zero arc, which the split's
        // minimum-arc rule rejects.
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
        // The recorded arc wins when the capture carries one: against 1 204 real swipes
        // the polyline runs 27% short at the median (ratio 0.73, p10 0.48), and for 141
        // of 971 the real arc demands two letters of a piece where the polyline demands
        // one. `Matcher.buildSegment` reads arc for minLetters, maxLetters and both
        // length bands.
        // Shape from the reconstruction, travel from the measurement: the resampled path
        // is untouched, so pass and endpoint geometry and DTW scores are unchanged.
        // Residual: MergeAlternatives recomputes a cut piece's arc from the sliced path,
        // so split pieces keep the short arc. Whole gestures are exact, and they make 81%
        // of the minLetters decisions.
        val recorded = ARC.find(m.groupValues[4])?.groupValues?.get(1)?.toFloatOrNull()
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        return SwipeToken(
            stream, path, resampled, contacts, recorded ?: arc, t0, t1,
            dwells = dwells(m.groupValues[4], path),
        )
    }

    /**
     * The pauses the capture recorded, placed on the rebuilt path by time. The merge cuts at a
     * dwell's midpoint, so the times are what it reads; the indices only bound the run.
     */
    private fun dwells(fields: String, path: List<PathPoint>): List<Dwell> {
        val at = fields.indexOf("dwell=")
        if (at < 0) return emptyList()
        val out = ArrayList<Dwell>(2)
        for (d in DWELL.findAll(fields.substring(at))) {
            val tIn = d.groupValues[1].toLong()
            val tOut = d.groupValues[2].toLong()
            val first = path.indexOfFirst { it.t >= tIn }.let { if (it < 0) path.size - 1 else it }
            val last = maxOf(first, path.indexOfLast { it.t <= tOut })
            out.add(Dwell(first, last, tIn, tOut))
        }
        return out
    }
}
