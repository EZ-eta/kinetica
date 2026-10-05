package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.SwipeToken
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
}
