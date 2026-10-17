package com.qtekfun.ultimatecamera.core.gpu.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrMergeTest {
    @Test
    fun alignmentFindsAShiftAcrossExposures() {
        val w = 64
        val h = 48

        // Blocky pseudo-random texture: no period, so only the true shift lines it up.
        fun scene(x: Int, y: Int): Float {
            val bx = x / 3
            val by = y / 3
            return 0.1f + 0.8f * (((bx * 73_856_093) xor (by * 19_349_663)) and 0xFF) / 255f
        }
        val ref = Plane(w, h)
        val moved = Plane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                ref[x, y] = scene(x, y)
                // Same scene, one stop darker, shifted by (+3, -2).
                moved[x, y] = scene((x - 3).coerceAtLeast(0), (y + 2).coerceAtMost(h - 1)) * 0.5f
            }
        }
        assertEquals(3 to -2, ExposureAlignment.shift(ref, moved, 5))
    }

    @Test
    fun radianceUndoesTheExposure() {
        val linear = 0.1f
        val values = doubleArrayOf(0.25, 1.0, 4.0).map { e -> linearToSrgb((linear * e).toFloat().coerceAtMost(1f)) }.toFloatArray()
        val r = GainMapMath.radiance(values, doubleArrayOf(0.25, 1.0, 4.0))
        assertEquals(linear, r, 0.01f)
    }

    @Test
    fun clippedEverywhereUsesTheDarkestFrame() {
        val r = GainMapMath.radiance(floatArrayOf(1f, 1f, 1f), doubleArrayOf(0.25, 1.0, 4.0))
        assertEquals(4f, r, 1e-4f)
    }

    @Test
    fun gainIsClampedAndEncodedLogarithmically() {
        assertEquals(1f, GainMapMath.gain(0.2f, 0.5f), 0f)
        assertEquals(GainMapMath.MAX_RATIO, GainMapMath.gain(10f, 0.5f), 0f)
        assertEquals(0, GainMapMath.encode(1f))
        assertEquals(255, GainMapMath.encode(GainMapMath.MAX_RATIO))
        assertEquals(128, GainMapMath.encode(2f))
    }

    private fun linearToSrgb(v: Float): Float = if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1 / 2.4) - 0.055).toFloat()
}
