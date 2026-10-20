package com.qtekfun.ultimatecamera.core.gpu

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneWhiteTest {
    private fun frame(vararg areas: Pair<Int, IntArray>): Pair<ByteBuffer, Int> {
        val count = areas.sumOf { it.first }
        val buffer = ByteBuffer.allocate(count * 4)
        for ((n, rgb) in areas) repeat(n) { buffer.put(rgb[0].toByte()).put(rgb[1].toByte()).put(rgb[2].toByte()).put(-1) }
        return buffer to count
    }

    private fun estimate(f: Pair<ByteBuffer, Int>, hlg: Boolean = false): FloatArray {
        val out = FloatArray(2)
        assertTrue(SceneWhite.illuminant(f.first, f.second, hlg, out))
        return out
    }

    @Test
    fun aNeutralGreyIsNeutral() {
        val out = estimate(frame(100 to intArrayOf(128, 128, 128)))
        assertEquals(1f, out[0], 1e-4f)
        assertEquals(1f, out[1], 1e-4f)
    }

    @Test
    fun aBrightWhiteBackgroundDecidesOverALargeBrownBox() {
        // Regression (the owner's viewfinder): a brown box over 80 % of the frame, a white wall behind it already shown
        // blue by the camera's AWB. Grey world would call the scene warm; the scene estimate must see the blue white.
        val out = estimate(frame(800 to intArrayOf(120, 85, 55), 200 to intArrayOf(185, 210, 240)))
        assertTrue("blue over green ${out[1]}", out[1] > 1.1f)
        assertTrue("red over green ${out[0]}", out[0] < 0.95f)
    }

    @Test
    fun clippedAndBlackPixelsAreLeftOut() {
        val out = estimate(frame(50 to intArrayOf(255, 255, 200), 50 to intArrayOf(2, 0, 9), 100 to intArrayOf(100, 120, 140)))
        val only = estimate(frame(100 to intArrayOf(100, 120, 140)))
        assertEquals(only[0], out[0], 1e-5f)
        assertEquals(only[1], out[1], 1e-5f)
    }

    @Test
    fun aFrameWithoutUsableLightGivesNothing() {
        val (buffer, count) = frame(100 to intArrayOf(255, 255, 255), 100 to intArrayOf(3, 3, 3))
        assertFalse(SceneWhite.illuminant(buffer, count, hlg = true, out = FloatArray(2)))
    }
}
