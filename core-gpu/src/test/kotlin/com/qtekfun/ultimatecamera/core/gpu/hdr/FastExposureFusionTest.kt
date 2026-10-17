package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastExposureFusionTest {
    private val w = 192
    private val h = 144

    /**
     * A scene with a dark room and a window three times brighter than the 0 EV frame can hold, with texture everywhere;
     * the frame at [gain] is the scene's linear light times [gain], clipped like a sensor, then sRGB-encoded.
     */
    private fun scene(gain: Float): RgbTile {
        val t = RgbTile(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val window = 1f / (1f + exp(-(x - w * 0.6f) / 6f))
                val linear = 0.01f + 3f * window + 0.004f * (1f + sin(x * 0.7f) * sin(y * 0.5f))
                t.r[x, y] = srgb(linear * gain)
                t.g[x, y] = srgb(linear * gain * 0.85f)
                t.b[x, y] = srgb(linear * gain * 0.7f)
            }
        }
        return t
    }

    private fun srgb(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1 / 2.4) - 0.055).toFloat()
    }

    private fun rows(t: RgbTile) = RowSource { y, r, g, b ->
        System.arraycopy(t.r.data, y * t.width, r, 0, t.width)
        System.arraycopy(t.g.data, y * t.width, g, 0, t.width)
        System.arraycopy(t.b.data, y * t.width, b, 0, t.width)
    }

    private class Collect(w: Int, h: Int) : BandSink {
        val out = RgbTile(w, h)
        val gain = FloatArray(w * h)
        val width = w

        override fun band(y0: Int, rows: Int, r: FloatArray, g: FloatArray, b: FloatArray, gain: FloatArray?) {
            synchronized(this) {
                System.arraycopy(r, 0, out.r.data, y0 * width, rows * width)
                System.arraycopy(g, 0, out.g.data, y0 * width, rows * width)
                System.arraycopy(b, 0, out.b.data, y0 * width, rows * width)
                gain?.let { System.arraycopy(it, 0, this.gain, y0 * width, rows * width) }
            }
        }
    }

    private fun fast(frames: List<RgbTile>, exposures: DoubleArray? = null, threads: Int = 4): Collect =
        Collect(w, h).also { FastExposureFusion(w, h).fuse(frames.map(::rows), exposures, it, threads) }

    private val bracket by lazy { listOf(scene(0.25f), scene(1f), scene(4f)) }

    @Test
    fun matchesTheReferenceFusionWithinTolerance() {
        val reference = ExposureFusion.fuse(bracket)
        val result = fast(bracket).out
        var sum = 0.0
        var worst = 0f
        for (c in 0 until 3) {
            val a = reference.channels[c].data
            val b = result.channels[c].data
            for (i in a.indices) {
                val d = abs(a[i] - b[i])
                sum += d
                worst = maxOf(worst, d)
            }
        }
        val mean = sum / (3.0 * w * h)
        assertTrue("mean difference $mean", mean < 0.03)
        assertTrue("worst difference $worst", worst < 0.2f)
    }

    @Test
    fun identicalExposuresComeBackUnchanged() {
        val t = scene(1f)
        val result = fast(listOf(t, t, t)).out
        for (i in t.r.data.indices) assertEquals(t.r.data[i], result.r.data[i], 1e-4f)
    }

    @Test
    fun theResultDoesNotDependOnTheThreadCount() {
        val one = fast(bracket, threads = 1).out
        val many = fast(bracket, threads = 6).out
        assertArrayEquals(one.r.data, many.r.data, 0f)
        assertArrayEquals(one.b.data, many.b.data, 0f)
    }

    @Test
    fun recoversTheWindowAndTheRoom() {
        val normal = bracket[1]
        val result = fast(bracket).out
        val windowNormal = (w - 20 until w - 4).map { normal.g[it, h / 2] }.average()
        val windowFused = (w - 20 until w - 4).map { result.g[it, h / 2] }.average()
        assertTrue("window $windowFused vs $windowNormal", windowFused < windowNormal)
        val roomNormal = (4 until 20).map { normal.g[it, h / 2] }.average()
        val roomFused = (4 until 20).map { result.g[it, h / 2] }.average()
        assertTrue("room $roomFused vs $roomNormal", roomFused > roomNormal)
    }

    @Test
    fun gainIsGivenOnTheQuarterGridOnly() {
        val result = fast(bracket, doubleArrayOf(0.25, 1.0, 4.0))
        val step = FastExposureFusion.GAIN_STEP
        // Inside the window the bracket knows radiance above what the fused SDR shows: gain above 1 there.
        val windowGain = result.gain[(h / 2 / step * step) * w + (w - 8) / step * step]
        assertTrue("window gain $windowGain", windowGain > 1f)
        assertEquals(0f, result.gain[(step + 1) * w + 1], 0f)
        for (y in 0 until h step step) for (x in 0 until w step step) assertTrue(result.gain[y * w + x] in 1f..GainMapMath.MAX_RATIO)
    }

    @Test
    fun parallelLowFusionEqualsTheSequentialOne() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val sequential = ExposureFusion.fuse(bracket, FastExposureFusion.LOW_LEVELS)
            val parallel = ExposureFusion.fuse(bracket, FastExposureFusion.LOW_LEVELS, pool)
            for (c in 0 until 3) assertArrayEquals(sequential.channels[c].data, parallel.channels[c].data, 0f)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun lowStageAlignsATranslatedFrame() {
        // Blocky pseudo-random texture at two exposures: alignment needs texture, not the smooth backlit scene.
        fun textured(gain: Float) = RgbTile(w, h).also { t ->
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = (0.1f + 0.8f * ((((x / 3) * 73_856_093) xor ((y / 3) * 19_349_663)) and 0xFF) / 255f) * gain
                    t.r[x, y] = v.coerceIn(0f, 1f)
                    t.g[x, y] = v.coerceIn(0f, 1f)
                    t.b[x, y] = v.coerceIn(0f, 1f)
                }
            }
        }
        val reference = textured(1f)
        // `moved` reads the darker frame 3 px left and 2 px down, so reading it 3 px right and 2 px up restores it.
        val moved = LowStage.translate(textured(0.5f), -3, 2)
        assertEquals(listOf(3 to -2, 0 to 0), LowStage.shifts(listOf(moved, reference), reference = 1))
    }

    @Test
    fun translateClampsAtTheEdges() {
        val t = RgbTile(3, 1)
        t.r.data[0] = 1f
        t.r.data[1] = 2f
        t.r.data[2] = 3f
        assertArrayEquals(floatArrayOf(2f, 3f, 3f), LowStage.translate(t, 1, 0).r.data, 0f)
    }

    @Test
    fun shiftedRowsReadFurtherWithClampedEdges() {
        val t = RgbTile(4, 2)
        for (i in 0 until 8) t.r.data[i] = i.toFloat()
        val r = FloatArray(4)
        ShiftedRows(rows(t), 4, 2, dx = 1, dy = 1).readRow(0, r, FloatArray(4), FloatArray(4))
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 7f), r, 0f)
    }
}
