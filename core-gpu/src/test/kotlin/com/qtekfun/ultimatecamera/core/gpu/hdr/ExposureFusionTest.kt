package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureFusionTest {
    private fun tile(w: Int, h: Int, f: (Int, Int) -> Float): RgbTile {
        val t = RgbTile(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = f(x, y)
                t.r[x, y] = v
                t.g[x, y] = (v * 0.9f).coerceIn(0f, 1f)
                t.b[x, y] = (v * 0.8f).coerceIn(0f, 1f)
            }
        }
        return t
    }

    /** A textured scene: a ramp with a checker on it, scaled by [gain] and clipped like a sensor. */
    private fun scene(w: Int, h: Int, gain: Float) = tile(w, h) { x, y ->
        val base = 0.05f + 0.9f * x / (w - 1)
        val texture = if ((x / 2 + y / 2) % 2 == 0) 0.04f else -0.04f
        ((base + texture) * gain).coerceIn(0f, 1f)
    }

    @Test
    fun wellExposednessPeaksAtMidGrey() {
        assertEquals(1f, ExposureFusion.wellExposed(0.5f), 1e-6f)
        assertTrue(ExposureFusion.wellExposed(0.5f) > ExposureFusion.wellExposed(0.9f))
        assertTrue(ExposureFusion.wellExposed(0.0f) < 0.05f)
        assertTrue(ExposureFusion.wellExposed(1.0f) < 0.05f)
    }

    @Test
    fun weightsFavourTexturedColourfulMidTones() {
        val mid = ExposureFusion.weights(scene(16, 16, 1f).also { it.b.data.fill(0.3f) })
        val clipped = ExposureFusion.weights(tile(16, 16) { _, _ -> 1f })
        assertTrue(mid.data.average() > clipped.data.average() * 1000)
    }

    @Test
    fun normalisedWeightsSumToOne() {
        val a = Plane(4, 4, FloatArray(16) { it.toFloat() })
        val b = Plane(4, 4, FloatArray(16) { 1f })
        val c = Plane(4, 4, FloatArray(16) { 0f })
        ExposureFusion.normalise(listOf(a, b, c))
        for (i in 0 until 16) assertEquals(1f, a.data[i] + b.data[i] + c.data[i], 1e-5f)
    }

    @Test
    fun laplacianPyramidCollapsesBackExactly() {
        val p = Plane(37, 23, FloatArray(37 * 23) { ((it * 7919) % 101) / 100f })
        val levels = ExposureFusion.levels(p.width, p.height, 6)
        val back = ExposureFusion.collapse(ExposureFusion.laplacianPyramid(p, levels))
        for (i in p.data.indices) assertEquals(p.data[i], back.data[i], 1e-5f)
    }

    @Test
    fun levelsStopAtTheMinimumSide() {
        assertEquals(1, ExposureFusion.levels(10, 10, 6))
        assertEquals(3, ExposureFusion.levels(40, 64, 6))
        assertEquals(6, ExposureFusion.levels(4000, 3000, 6))
    }

    @Test
    fun fusingIdenticalExposuresReturnsTheImage() {
        val t = scene(32, 24, 1f)
        val fused = ExposureFusion.fuse(listOf(t, t, t))
        for (i in t.r.data.indices) assertEquals(t.r.data[i], fused.r.data[i], 1e-4f)
    }

    @Test
    fun fusionRecoversHighlightsAndShadows() {
        val w = 64
        val h = 32
        val under = scene(w, h, 0.25f)
        val normal = scene(w, h, 1f)
        val over = scene(w, h, 4f)
        val fused = ExposureFusion.fuse(listOf(under, normal, over))
        // Bright end: the normal frame is clipped near 1 there; the fused one keeps detail (below clipping).
        val brightNormal = (w - 8 until w - 2).map { normal.r[it, h / 2] }.average()
        val brightFused = (w - 8 until w - 2).map { fused.r[it, h / 2] }.average()
        assertTrue("highlights $brightFused vs $brightNormal", brightFused < brightNormal)
        // Dark end: the fused frame is lifted above the normal frame.
        val darkNormal = (2 until 8).map { normal.r[it, h / 2] }.average()
        val darkFused = (2 until 8).map { fused.r[it, h / 2] }.average()
        assertTrue("shadows $darkFused vs $darkNormal", darkFused > darkNormal)
        assertTrue(fused.r.data.all { it in 0f..1f })
    }

    @Test
    fun expandKeepsAConstantPlaneConstant() {
        val p = Plane(5, 3, FloatArray(15) { 0.42f })
        val up = ExposureFusion.expand(p, 10, 6)
        assertTrue(up.data.all { abs(it - 0.42f) < 1e-6f })
    }
}
