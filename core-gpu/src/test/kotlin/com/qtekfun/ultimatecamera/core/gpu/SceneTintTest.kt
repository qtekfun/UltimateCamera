package com.qtekfun.ultimatecamera.core.gpu

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTintTest {
    @Test
    fun aCyanCastIsRemovedWithTheSmallestGainAtOne() {
        val t = SceneTint(hlg = true)
        t.update(0.88f, 0.98f)
        // Red is lifted most; green, the strongest channel, stays at 1 so a clipped white stays white.
        assertEquals(1f / 0.88f / (1f / 0.98f) * 1f, t.linearGain(0) / t.linearGain(2), 1e-4f)
        assertEquals(1f, t.linearGain(1), 1e-6f)
        assertTrue(t.linearGain(0) > t.linearGain(2))
        // HLG signal gains are the square root of the linear ones (exact in its lower half).
        assertEquals(t.linearGain(0).toDouble().pow(0.5).toFloat(), t.signalGains[0], 1e-5f)
    }

    @Test
    fun laterMeasurementsMovePartOfTheWayAndResetUndoesEverything() {
        val t = SceneTint(hlg = false)
        t.update(1f, 1f)
        assertEquals(1f, t.signalGains[0], 1e-6f)
        t.update(0.5f, 1f)
        assertEquals(2.0.pow(SceneTint.SMOOTHING).toFloat(), t.linearGain(0), 1e-4f)
        t.reset()
        assertEquals(listOf(1f, 1f, 1f), t.signalGains.toList())
    }

    @Test
    fun gainsAreCapped() {
        val t = SceneTint(hlg = false)
        t.update(0.1f, 1f)
        assertEquals(SceneTint.MAX_GAIN.toFloat(), t.linearGain(0), 1e-4f)
    }
}
