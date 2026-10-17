package com.qtekfun.ultimatecamera.core.gpu

import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UcLogTest {
    @Test
    fun documentedCodeValues() {
        assertEquals(36, UcLog.code10(0f))
        assertEquals(409, UcLog.code10(0.18f))
        assertEquals(637, UcLog.code10(0.90f))
        assertEquals(1023, UcLog.code10(UcLog.MAX_LINEAR))
        assertEquals(12.504375f, UcLog.MAX_LINEAR, 1e-4f)
        assertEquals(0.035384f, UcLog.BLACK_SIGNAL, 1e-6f)
        assertEquals(1f, UcLog.encode(UcLog.MAX_LINEAR), 1e-6f)
    }

    @Test
    fun roundTripsAcrossTheWholeRange() {
        var x = -0.003f
        while (x < UcLog.MAX_LINEAR) {
            val back = UcLog.decode(UcLog.encode(x))
            assertEquals("x=$x", x, back, 1e-5f + abs(x) * 1e-5f)
            x += if (x < 1f) 0.0005f else 0.01f
        }
        var y = 0f
        while (y <= 1f) {
            assertEquals("y=$y", y, UcLog.encode(UcLog.decode(y)), 1e-5f)
            y += 1f / 1023f
        }
    }

    @Test
    fun strictlyMonotonicAndContinuousAtTheToe() {
        var previous = UcLog.encode(-0.004f)
        var x = -0.004f + 1e-4f
        while (x < UcLog.MAX_LINEAR) {
            val y = UcLog.encode(x)
            assertTrue("not increasing at $x", y > previous)
            previous = y
            x += if (x < 0.5f) 1e-4f else 1e-2f
        }
        // Value and slope match where the linear toe meets the log curve.
        val e = 1e-4f
        assertEquals(UcLog.encode(-e), UcLog.encode(e), 2.5f * UcLog.TOE_SLOPE * e)
        val slopeAbove = (UcLog.encode(2 * e) - UcLog.encode(e)) / e
        assertEquals(UcLog.TOE_SLOPE, slopeAbove, UcLog.TOE_SLOPE * 0.02f)
    }

    /** REQ-COL-005: one 10-bit code is at most 1 % of linear light from two stops under grey to clip. */
    @Test
    fun tenBitStepsStayUnderOnePercentFromMinusTwoStops() {
        val from = UcLog.encode(0.18f * 2f.pow(-2))
        var code = (from * 1023).toInt() + 1
        var worst = 0.0
        while (code < 1023) {
            val a = UcLog.decode(code / 1023f).toDouble()
            val b = UcLog.decode((code + 1) / 1023f).toDouble()
            worst = maxOf(worst, b / a - 1)
            code++
        }
        assertTrue("worst step ${worst * 100} %", worst < 0.01)
        // 94 codes per stop at grey.
        val perStop = (UcLog.encode(0.18f * 2f.pow(0.5f)) - UcLog.encode(0.18f * 2f.pow(-0.5f))) * 1023
        assertEquals(94.0, perStop.toDouble(), 1.0)
    }

    @Test
    fun cameraTransfersMapGreyAndPeak() {
        // BT.2408: 18 % grey at 38 % HLG; BT.709 OETF puts 0.18 at ≈ 0.409.
        assertEquals(0.40f, UcLog.fromHlg(0.38f), 1e-5f)
        assertEquals(0.40f, UcLog.fromBt709(0.409f), 2e-3f)
        assertEquals(845, (UcLog.fromHlg(1f) * 1023 + 0.5f).toInt())
        assertEquals(1f, UcLog.hlgToLinear(1f), 1e-6f)
        assertEquals(1f / 12f, UcLog.hlgToLinear(0.5f), 1e-6f)
        assertEquals(0f, UcLog.fromHlg(0f) - UcLog.BLACK_SIGNAL, 1e-6f)
    }

    @Test
    fun rec709LutIsNeutralMonotonicAndMatchesTheFormula() {
        val lut = UcLogLuts.toRec709(33)
        val out = FloatArray(3)
        var previous = -1f
        for (i in 0..100) {
            val y = i / 100f
            LutMath.apply(lut, y, y, y, out)
            assertEquals(out[0], out[1], 2f / 255f) // trilinear across the clipped gamut matrix
            assertEquals(out[1], out[2], 2f / 255f) // trilinear across the clipped gamut matrix
            assertTrue("not increasing at $y", out[1] >= previous)
            previous = out[1]
            val expected = UcLogLuts.display(maxOf(0f, UcLog.decode(y)).toDouble())
            assertEquals("y=$y", expected, out[1], 1f / 255f) // under one 8-bit code, worst in the deepest shadows
        }
        LutMath.apply(lut, 0.4f, 0.4f, 0.4f, out)
        assertEquals(0.409f, out[1], 2e-3f)
        LutMath.apply(lut, 0f, 0f, 0f, out)
        assertEquals(0f, out[1], 1e-6f)
        val identity = UcLogLuts.identity(9)
        LutMath.apply(identity, 0.3f, 0.6f, 0.9f, out)
        assertEquals(0.6f, out[1], 1e-6f)
    }

    @Test
    fun shaderSourceCarriesTheSameConstants() {
        val glsl = UcLogShaders.FUNCTIONS
        assertTrue(glsl, glsl.contains("UC_GREY_SIGNAL = 0.40000"))
        assertTrue(glsl, glsl.contains("UC_STOP_SLOPE = 0.10000"))
        assertTrue(glsl, glsl.contains("UC_OFFSET = 0.0156250"))
        val fragment = LutShaders.fragment(InputKind.EXTERNAL_OES, LutInterpolation.MANUAL_TRILINEAR, LogSource.HLG)
        assertTrue(fragment.startsWith("#version 300 es\n"))
        assertTrue(fragment, fragment.contains("c = ucLogFromHlg(c);"))
        val plain = LutShaders.fragment(InputKind.TEXTURE_2D, null)
        assertTrue(!plain.contains("ucLog") && !plain.contains("//@"))
    }
}
