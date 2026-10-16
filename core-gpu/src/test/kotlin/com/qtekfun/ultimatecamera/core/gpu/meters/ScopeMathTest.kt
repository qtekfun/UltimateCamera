package com.qtekfun.ultimatecamera.core.gpu.meters

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeMathTest {
    private val chart = MeterTestCharts.scopeChart()
    private val w = MeterTestCharts.SCOPE_WIDTH
    private val h = MeterTestCharts.SCOPE_HEIGHT

    @Test
    fun integerLumaMatchesTheRec709Formula() {
        for (v in 0..255) assertEquals(v, ScopeBins.lumaLevel(v, v, v))
        for (r in 0..255 step 15) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 51) {
                    val exact = Luma.of(r.toFloat(), g.toFloat(), b.toFloat())
                    assertTrue("($r,$g,$b)", abs(ScopeBins.lumaLevel(r, g, b) - exact) <= 0.5001f)
                }
            }
        }
    }

    @Test
    fun vectorscopeCellsOfPrimariesAndGrey() {
        val s = 128
        assertEquals("grey is the centre", 64, ScopeBins.vectorscopeX(128, 128, 128, s))
        assertEquals(64, ScopeBins.vectorscopeY(128, 128, 128, s))
        assertEquals("blue has the largest Cb", s - 1, ScopeBins.vectorscopeX(0, 0, 255, s))
        assertEquals("yellow the smallest", 0, ScopeBins.vectorscopeX(255, 255, 0, s))
        assertEquals("red has the largest Cr", s - 1, ScopeBins.vectorscopeY(255, 0, 0, s))
        assertEquals("cyan the smallest", 0, ScopeBins.vectorscopeY(0, 255, 255, s))
        // The integer cells agree with the float formula the UI uses for graticule targets.
        for ((r, g, b) in listOf(Triple(191, 0, 0), Triple(0, 191, 0), Triple(191, 0, 191), Triple(0, 191, 191))) {
            val (px, py) = ScopeBins.vectorscopePosition(r / 255f, g / 255f, b / 255f)
            assertEquals((px * s).toInt().coerceAtMost(s - 1).toFloat(), ScopeBins.vectorscopeX(r, g, b, s).toFloat(), 1f)
            assertEquals((py * s).toInt().coerceAtMost(s - 1).toFloat(), ScopeBins.vectorscopeY(r, g, b, s).toFloat(), 1f)
        }
    }

    @Test
    fun histogramLayersKeepEveryTexelWithinCapacity() {
        val n = 240 * 135
        val layers = ScopeBins.histogramLayers(n, AccumulationCapacity.HALF_FLOAT)
        assertEquals(16, layers)
        assertEquals(128, ScopeBins.histogramLayers(n, AccumulationCapacity.UNORM8))
        val flat = IntArray(n) { 0xFF808080.toInt() }
        val counts = ScopeMath.histogram(flat, layers)
        assertTrue(counts.all { it <= AccumulationCapacity.HALF_FLOAT })
        assertEquals(n, ScopeMath.sumLayers(counts, layers)[128 * 4 + ScopeMath.Y])
    }

    @Test
    fun histogramTotalsCountEverySampleOncePerChannel() {
        val layers = 3
        val totals = ScopeMath.sumLayers(ScopeMath.histogram(chart, layers), layers)
        for (c in 0..3) assertEquals(chart.size, (0 until ScopeMath.LEVELS).sumOf { totals[it * 4 + c] })
        assertEquals("luma 0: the black bar (12 rows × 8 px) and the ramp start (12)", 12 * 8 + 12, totals[ScopeMath.Y])
    }

    @Test
    fun waveformOfTheRampIsADiagonal() {
        val counts = ScopeMath.waveform(chart, w, AccumulationCapacity.HALF_FLOAT)
        for (x in 0 until w) {
            val level = x * 255 / (w - 1)
            assertTrue("column $x has the ramp's 12 rows at level $level", counts[(level * w + x) * 4 + ScopeMath.Y] >= 12)
        }
    }

    @Test
    fun countsSaturateAtCapacity() {
        val flat = IntArray(1000) { 0xFF404040.toInt() }
        assertEquals(255, ScopeMath.vectorscope(flat, 64, AccumulationCapacity.UNORM8)[32 * 64 + 32])
        assertEquals(1000, ScopeMath.vectorscope(flat, 64, AccumulationCapacity.HALF_FLOAT)[32 * 64 + 32])
    }

    @Test
    fun downsampleOfABlockImageIsExact() {
        val big = MeterTestCharts.blockUpscale(chart, w, h, MeterTestCharts.SCOPE_BLOCK)
        val small = ScopeMath.downsample(big, w * MeterTestCharts.SCOPE_BLOCK, h * MeterTestCharts.SCOPE_BLOCK, w, h)
        assertTrue(chart.contentEquals(small))
    }

    @Test
    fun analysisSizeFollowsTheDivisorAndCap() {
        assertEquals(240 to 135, ScopeSettings(analysisDivisor = 8).analysisSize(1920, 1080))
        assertEquals(480 to 270, ScopeSettings(analysisDivisor = 4).analysisSize(1920, 1080))
        assertEquals(480 to 270, ScopeSettings(analysisDivisor = 2).analysisSize(1920, 1080))
    }

    @Test
    fun histogramDisplayBarHeightIsLinearInCount() {
        val totals = IntArray(ScopeMath.LEVELS * 4)
        val s = ScopeSettings(histogram = true, histogramMode = HistogramMode.LUMA, histogramScale = 1f)
        val n = 256 * 10
        totals[0 * 4 + ScopeMath.Y] = 5
        val image = ScopeDisplayMath.histogramDisplay(totals, n, s, 256, 20)
        val lit = (0 until 20).count { image[it * 256] != 0xFF000000.toInt() }
        assertEquals((20 * 0.5f).roundToInt(), lit)
    }

    @Test
    fun costHistoryPercentile() {
        val history = CostHistory(capacity = 100)
        assertEquals(null, history.summary())
        for (i in 1..200) history.add(i.toFloat())
        val summary = history.summary()!!
        assertEquals(100, summary.samples)
        assertEquals(195f, summary.p95Ms)
        assertEquals(200f, summary.maxMs)
        assertEquals(150.5f, summary.meanMs, 1e-3f)
    }
}
