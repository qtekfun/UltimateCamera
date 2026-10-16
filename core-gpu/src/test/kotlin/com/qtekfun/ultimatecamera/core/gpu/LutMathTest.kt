package com.qtekfun.ultimatecamera.core.gpu

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LutMathTest {
    private val out = FloatArray(3)

    @Test
    fun identityLutKeepsColours() {
        val lut = CubeLut.identity(17)
        val random = Random(1)
        repeat(1_000) {
            val r = random.nextFloat()
            val g = random.nextFloat()
            val b = random.nextFloat()
            LutMath.apply(lut, r, g, b, out)
            assertArrayEquals(floatArrayOf(r, g, b), out, 1e-5f)
        }
    }

    @Test
    fun latticePointsReturnTheStoredValueExactly() {
        val lut = SpikeLuts.creative(9)
        for (b in 0 until 9) {
            for (g in 0 until 9) {
                for (r in 0 until 9) {
                    LutMath.apply(lut, r / 8f, g / 8f, b / 8f, out)
                    val i = lut.index(r, g, b)
                    assertArrayEquals(lut.data.copyOfRange(i, i + 3), out, 1e-5f)
                }
            }
        }
    }

    @Test
    fun interpolatesLinearlyBetweenLatticePoints() {
        // A LUT that is linear in each channel must be reproduced exactly between its lattice points.
        val lut = CubeLut.generate(5) { r, g, b -> Triple(1f - r, g * 0.5f, b * b) }
        LutMath.apply(lut, 0.1f, 0.3f, 0.5f, out)
        assertEquals(0.9f, out[0], 1e-5f)
        assertEquals(0.15f, out[1], 1e-5f)
        assertEquals(0.25f, out[2], 1e-5f) // 0.5 is a lattice point for b.
    }

    @Test
    fun clampsOutsideTheDomain() {
        val lut = CubeLut.identity(5)
        LutMath.apply(lut, -1f, 2f, 0.5f, out)
        assertArrayEquals(floatArrayOf(0f, 1f, 0.5f), out, 1e-6f)
    }
}
