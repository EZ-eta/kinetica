package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a replayed buffer carries back from the capture line. */
class TraceReplayTest {

    private val g = TestData.qwertyGeometry()

    @Test
    fun aRecordedDwellComesBackOnTheRebuiltPath() {
        // The merge cuts a swipe at a dwell's midpoint, so a replay without them never ran
        // that generator.
        val line = "decode in[it]: tap[c,LEFT,t=38005068] swipe[LEFT,t=38005561..38006083,keys=q@0-273," +
            "w@273-345,e@345-522,dwell=38005561-38005802/0.26kw/55n;38005900-38006000/0.12kw/20n] ctx=[]"
        val s = TraceReplay.tokens(line, g).filterIsInstance<SwipeToken>().single()
        assertEquals(listOf(38005561L to 38005802L, 38005900L to 38006000L), s.dwells.map { it.tEnter to it.tExit })
        for (d in s.dwells) {
            assertTrue(d.enterIdx in 0..d.exitIdx && d.exitIdx < s.rawPath.size)
            assertTrue(s.rawPath[d.enterIdx].t >= d.tEnter && s.rawPath[d.exitIdx].t <= d.tExit)
        }
    }

    /** A wavering stroke over t-h-e drawn through a real engine, with its trace lines kept. */
    private fun drawn(lines: MutableList<String>): SwipeToken {
        val got = ArrayList<InputToken>()
        val engine = GestureEngine(object : GestureEngine.Listener {
            override fun onTokenFinalized(token: InputToken) { got.add(token) }
            override fun onKeyTransition(streamId: StreamId, code: Int) {}
            override fun onAllPointersUp() {}
        }).also { it.setGeometry(g, 30f) }
        DecodeTrace.sink = { lines.add(it) }
        try {
            val k = g.keyWidthPx
            val stops = "the".map { g.centerX(it - 'a') to g.centerY(it - 'a') }
            var t = 1_000L
            engine.onPointerDown(0, stops[0].first * k, stops[0].second * k, t)
            for (i in 1 until stops.size) {
                for (s in 1..12) {
                    val f = s / 12f
                    val x = stops[i - 1].first + f * (stops[i].first - stops[i - 1].first)
                    val y = stops[i - 1].second + f * (stops[i].second - stops[i - 1].second) + 0.13f * (s % 3 - 1)
                    t += 7
                    engine.onPointerMove(0, x * k, y * k, t)
                }
            }
            engine.onPointerUp(0, stops.last().first * k, stops.last().second * k, t + 5)
        } finally {
            DecodeTrace.sink = null
        }
        return got.single() as SwipeToken
    }

    @Test
    fun aStrokeRebuiltFromItsTracedPathKeepsItsPointsAndKeys() {
        // A polyline through key centres led with another word than the phone on most long
        // strokes; the traced points must bring the stroke back as it was drawn.
        val lines = ArrayList<String>()
        val s = drawn(lines)
        val board = requireNotNull(lines.firstNotNullOfOrNull { TraceReplay.board(it) })
        val path = requireNotNull(lines.firstNotNullOfOrNull { TraceReplay.path(it) })
        val back = requireNotNull(TraceReplay.rebuild(path, board))
        assertEquals(s.rawPath.size, back.rawPath.size)
        for ((a, b) in s.rawPath.zip(back.rawPath)) {
            assertTrue("x ${a.x} vs ${b.x}", abs(a.x - b.x) <= 0.006f)
            assertTrue("y ${a.y} vs ${b.y}", abs(a.y - b.y) <= 0.006f)
            assertEquals(a.t, b.t)
        }
        assertEquals(s.keyContacts, back.keyContacts)
        assertEquals(s.tStart to s.tEnd, back.tStart to back.tEnd)
        assertTrue(abs(s.arcLen - back.arcLen) <= 0.02f * s.arcLen)
    }

    @Test
    fun theBoardComesBackFromItsLine() {
        val line = GestureEngine.geometryLine(g, 0.3f)
        val board = requireNotNull(TraceReplay.board(line))
        assertEquals(0.3f, board.tapDispKw, 1e-4f)
        assertEquals(g.midlinePx / g.keyWidthPx, board.g.midlinePx / board.g.keyWidthPx, 0.006f)
        for (code in 0 until 26) {
            assertEquals(g.hasKey(code), board.g.hasKey(code))
            if (!g.hasKey(code)) continue
            assertEquals(g.centerX(code), board.g.centerX(code), 0.006f)
            assertEquals(g.centerY(code), board.g.centerY(code), 0.006f)
        }
    }

    @Test
    fun aCaptureWritesTheBoardOnceAndEachStrokesPathAgain() {
        val lines = ArrayList<String>()
        drawn(lines)
        assertEquals(1, lines.count { it.startsWith("geometry ") })
        assertEquals(1, lines.count { it.startsWith("path ") })
        assertTrue(lines.indexOfFirst { it.startsWith("geometry ") } < lines.indexOfFirst { it.startsWith("path ") })
    }

    @Test
    fun aClearedTraceGetsTheBoardAgain() {
        // A trace cleared before a capture loses the board line written before the clear, so the
        // first stroke after it writes the line again.
        val lines = ArrayList<String>()
        val got = ArrayList<InputToken>()
        val engine = GestureEngine(object : GestureEngine.Listener {
            override fun onTokenFinalized(token: InputToken) { got.add(token) }
            override fun onKeyTransition(streamId: StreamId, code: Int) {}
            override fun onAllPointersUp() {}
        }).also { it.setGeometry(g, 30f) }
        val k = g.keyWidthPx
        fun stroke(t: Long) {
            engine.onPointerDown(0, g.centerX('q' - 'a') * k, g.centerY('q' - 'a') * k, t)
            engine.onPointerMove(0, g.centerX('e' - 'a') * k, g.centerY('e' - 'a') * k, t + 40)
            engine.onPointerUp(0, g.centerX('t' - 'a') * k, g.centerY('t' - 'a') * k, t + 80)
        }
        DecodeTrace.sink = { lines.add(it) }
        try {
            stroke(0)
            stroke(500)
            DecodeTrace.epoch++
            stroke(1_000)
        } finally {
            DecodeTrace.sink = null
        }
        assertEquals(3, lines.count { it.startsWith("path ") })
        assertEquals(2, lines.count { it.startsWith("geometry ") })
    }
}
