package com.qtekfun.ultimatecamera.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyFrameGateTest {
    @Test
    fun sameRateKeepsEveryFrameWhileTheEncoderKeepsUp() {
        val gate = ProxyFrameGate(sourceFps = 30, targetFps = 30)
        repeat(300) { assertTrue(gate.shouldDraw(encodedSoFar = gate.submitted)) }
        assertEquals(0, gate.skippedBehind)
    }

    @Test
    fun sixtyToThirtyKeepsEveryOtherFrame() {
        val gate = ProxyFrameGate(sourceFps = 60, targetFps = 30)
        val kept = (0 until 60).map { gate.shouldDraw(gate.submitted) }
        assertEquals(30, kept.count { it })
        // Evenly spaced: never two kept or two dropped in a row.
        kept.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
    }

    @Test
    fun fiftyToThirtyKeepsThreeOfFive() {
        val gate = ProxyFrameGate(sourceFps = 50, targetFps = 30)
        assertEquals(300, (0 until 500).count { gate.shouldDraw(gate.submitted) })
    }

    @Test
    fun aTargetAboveTheSourceKeepsEveryFrame() {
        val gate = ProxyFrameGate(sourceFps = 24, targetFps = 30)
        assertEquals(24, (0 until 24).count { gate.shouldDraw(gate.submitted) })
    }

    @Test
    fun aStalledEncoderMakesTheGateSkipInsteadOfQueue() {
        val gate = ProxyFrameGate(sourceFps = 30, targetFps = 30, maxBacklog = 4)
        // The encoder produces nothing: four frames go in, then everything is skipped.
        val kept = (0 until 20).count { gate.shouldDraw(encodedSoFar = 0) }
        assertEquals(4, kept)
        assertEquals(16, gate.skippedBehind)
        // Once it catches up, frames flow again.
        assertTrue(gate.shouldDraw(encodedSoFar = 4))
        assertFalse(gate.shouldDraw(encodedSoFar = 0))
    }
}
