package com.qtekfun.ultimatecamera.core.gpu

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LutComposeTest {
    private val out = FloatArray(3)
    private val expected = FloatArray(3)

    @Test
    fun identityOnEitherSideKeepsTheOtherLut() {
        val creative = SpikeLuts.creative(17)
        val random = Random(2)
        for (composed in listOf(LutCompose.compose(CubeLut.identity(17), creative), LutCompose.compose(creative, CubeLut.identity(17)))) {
            repeat(500) {
                val r = random.nextFloat()
                val g = random.nextFloat()
                val b = random.nextFloat()
                LutMath.apply(composed, r, g, b, out)
                LutMath.apply(creative, r, g, b, expected)
                assertArrayEquals(expected, out, 2e-3f)
            }
        }
    }

    @Test
    fun appliesTheFirstLutThenTheSecondAtLatticePoints() {
        val first = CubeLut.generate(9) { r, g, b -> Triple(r * r, g * 0.5f, 1f - b) }
        val second = CubeLut.generate(9) { r, g, b -> Triple(b, r, g) }
        val composed = LutCompose.compose(first, second)
        val step = 1f / 8
        val mid = FloatArray(3)
        for (i in 0 until 9) {
            val v = i * step
            LutMath.apply(first, v, v, v, mid)
            LutMath.apply(second, mid[0], mid[1], mid[2], expected)
            LutMath.apply(composed, v, v, v, out)
            assertArrayEquals(expected, out, 1e-5f)
        }
    }

    @Test
    fun sizeIsTheLargerOneCappedAt65() {
        assertEquals(33, LutCompose.compose(CubeLut.identity(17), CubeLut.identity(33)).size)
        assertEquals(LutCompose.MAX_SIZE, LutCompose.compose(CubeLut.identity(2), CubeLut.identity(80)).size)
    }

    @Test
    fun keepsTheFirstLutsDomain() {
        val first = CubeLut(2, CubeLut.identity(2).data, "wide", floatArrayOf(-1f, -1f, -1f), floatArrayOf(2f, 2f, 2f))
        val composed = LutCompose.compose(first, CubeLut.identity(2))
        assertArrayEquals(first.domainMin, composed.domainMin, 0f)
        assertArrayEquals(first.domainMax, composed.domainMax, 0f)
    }
}
