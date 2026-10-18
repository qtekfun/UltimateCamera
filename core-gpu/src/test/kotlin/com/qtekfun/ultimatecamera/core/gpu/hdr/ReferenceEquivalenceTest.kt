package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.Random
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D90 rewrote the CPU photo code for speed (flat loops, fewer allocations, work spread over the cores). The output
 * must not move by a single bit: every rewritten step is compared with its pre-D90 copy ([ReferenceFusion],
 * [ReferenceAlignment], [ReferencePhotoLook], [ReferenceGuidedBase]) on noisy synthetic images.
 */
class ReferenceEquivalenceTest {
    private val pool = Executors.newFixedThreadPool(4)

    @After
    fun tearDown() {
        pool.shutdown()
    }

    private fun plane(w: Int, h: Int, seed: Long): Plane {
        val random = Random(seed)
        return Plane(w, h, FloatArray(w * h) { i -> (0.5f + 0.4f * kotlin.math.sin(i * 0.013f) + 0.1f * random.nextFloat()).coerceIn(0f, 1f) })
    }

    private fun tile(w: Int, h: Int, seed: Long) = RgbTile(w, h, plane(w, h, seed), plane(w, h, seed + 1), plane(w, h, seed + 2))

    private fun assertSame(expected: Plane, actual: Plane) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals(expected.data, actual.data, 0f)
    }

    private fun assertSame(expected: RgbTile, actual: RgbTile) {
        assertSame(expected.r, actual.r)
        assertSame(expected.g, actual.g)
        assertSame(expected.b, actual.b)
    }

    @Test
    fun pyramidStepsMatchToTheBit() {
        for ((w, h) in listOf(61 to 47, 64 to 64, 9 to 5)) {
            val p = plane(w, h, 3)
            assertSame(ReferenceFusion.reduce(p), ExposureFusion.reduce(p))
            assertSame(ReferenceFusion.reduce(p), ExposureFusion.reduce(p, pool))
            assertSame(ReferenceFusion.expand(p, w * 2 - 1, h * 2 + 1), ExposureFusion.expand(p, w * 2 - 1, h * 2 + 1, pool))
            val levels = ExposureFusion.levels(w, h, 5)
            ReferenceFusion.laplacianPyramid(p, levels).zip(ExposureFusion.laplacianPyramid(p, levels, pool)).forEach { (a, b) -> assertSame(a, b) }
            val lp = ReferenceFusion.laplacianPyramid(p, levels)
            assertSame(ReferenceFusion.collapse(lp), ExposureFusion.collapse(lp, pool))
        }
    }

    @Test
    fun weightsNormalisationAndFusionMatchToTheBit() {
        val frames = listOf(tile(83, 61, 10), tile(83, 61, 20), tile(83, 61, 30))
        frames.forEach { assertSame(ReferenceFusion.weights(it), ExposureFusion.weights(it)) }
        val reference = frames.map(ReferenceFusion::weights).also(ReferenceFusion::normalise)
        val banded = ExposureFusion.weights(frames, pool).also { ExposureFusion.normalise(it, pool) }
        reference.zip(banded).forEach { (a, b) -> assertSame(a, b) }
        val expected = ReferenceFusion.fuse(frames, 5)
        assertSame(expected, ExposureFusion.fuse(frames, 5))
        assertSame(expected, ExposureFusion.fuse(frames, 5, pool))
        assertSame(expected, ExposureFusion.fuseWeighted(frames, banded, 5, pool))
        assertSame(ReferenceFusion.fuse(frames, 5, pool), ExposureFusion.fuse(frames, 5, pool))
    }

    @Test
    fun weightsOfAZeroImageFallBackToEqualShares() {
        val black = List(2) { RgbTile(8, 8) }
        val reference = black.map(ReferenceFusion::weights).also(ReferenceFusion::normalise)
        val ours = ExposureFusion.weights(black, pool).also { ExposureFusion.normalise(it, pool) }
        reference.zip(ours).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test
    fun alignmentFindsTheSameShifts() {
        val ref = plane(200, 150, 7)
        for ((dx, dy) in listOf(3 to -2, -5 to 4, 0 to 0, 7 to 7)) {
            val moved = LowStage.translate(RgbTile(200, 150, ref, ref, ref), dx, dy).r
            for (radius in listOf(2, 4, 8)) {
                val expected = ReferenceAlignment.shiftPyramid(ref, moved, radius)
                assertEquals(expected, ExposureAlignment.shiftPyramid(ref, moved, radius))
                assertEquals(expected, ExposureAlignment.shiftPyramid(ref, moved, radius, pool))
                assertEquals(ReferenceAlignment.shift(ref, moved, 2), ExposureAlignment.shift(ref, moved, 2, pool))
            }
        }
        assertSame(ReferenceAlignment.halve(ref), ExposureAlignment.halve(ref))
    }

    @Test
    fun lowStageShiftsMatchWithAndWithoutPool() {
        val base = tile(160, 120, 40)
        val low = listOf(LowStage.translate(base, 2, -1), base, LowStage.translate(base, -3, 2))
        assertEquals(LowStage.shifts(low, 1), LowStage.shifts(low, 1, pool = pool))
    }

    @Test
    fun guidedBaseMatchesToTheBit() {
        val low = Plane(97, 71, FloatArray(97 * 71) { i -> -6f + 4f * kotlin.math.sin(i * 0.05f) })
        val expected = ReferenceGuidedBase.of(low, 5, 0.25f)
        for (actual in listOf(GuidedBase.of(low, 5, 0.25f), GuidedBase.of(low, 5, 0.25f, pool))) {
            assertSame(expected.a, actual.a)
            assertSame(expected.b, actual.b)
        }
    }

    private class Rendered(val pixels: IntArray, val black: Float, val white: Float, val saturation: Float)

    private fun source(rgb: IntArray, width: Int) = RowSource { y, r, g, b ->
        for (x in 0 until width) {
            val p = rgb[y * width + x]
            r[x] = ((p shr 16) and 0xFF) / 255f
            g[x] = ((p shr 8) and 0xFF) / 255f
            b[x] = (p and 0xFF) / 255f
        }
    }

    private fun sink(out: IntArray, width: Int) = BandSink { y0, rows, r, g, b, _ ->
        for (i in 0 until rows * width) out[y0 * width + i] = (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
    }

    private fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun reference(rgb: IntArray): Rendered {
        val out = IntArray(rgb.size)
        val look = ReferencePhotoLook(SyntheticScenes.WIDTH, SyntheticScenes.HEIGHT)
        look.apply(source(rgb, SyntheticScenes.WIDTH), sink(out, SyntheticScenes.WIDTH))
        return Rendered(out, look.blackPoint, look.whiteGain, look.saturation)
    }

    @Test
    fun photoLookMatchesItsPreviousSelfToTheBit() {
        for (scene in listOf(SyntheticScenes.washedOut(), SyntheticScenes.indoorJpegR(), SyntheticScenes.darkFusion())) {
            val expected = reference(scene)
            val out = IntArray(scene.size)
            val look = PhotoLook(SyntheticScenes.WIDTH, SyntheticScenes.HEIGHT)
            look.apply(source(scene, SyntheticScenes.WIDTH), sink(out, SyntheticScenes.WIDTH))
            assertArrayEquals(expected.pixels, out)
            assertEquals(expected.black, look.blackPoint, 0f)
            assertEquals(expected.white, look.whiteGain, 0f)
            assertEquals(expected.saturation, look.saturation, 0f)
            assertEquals(listOf("measure", "tone", "base", "render"), look.timings.keys.toList())
        }
    }

    @Test
    fun prepareThenRenderInTwoGoesIsApply() {
        val scene = SyntheticScenes.indoorJpegR()
        val expected = reference(scene).pixels
        val look = PhotoLook(SyntheticScenes.WIDTH, SyntheticScenes.HEIGHT)
        val src = source(scene, SyntheticScenes.WIDTH)
        val plan = look.prepare(src, threads = 2)
        assertEquals(look.factor, plan.factor)
        assertEquals(look.lowW, plan.lowW)
        assertEquals(look.lowH, plan.lowH)
        // A GPU pass that stopped after 130 rows: the CPU finishes from there and only there.
        val out = IntArray(scene.size) { -1 }
        look.render(plan, src, sink(out, SyntheticScenes.WIDTH), from = 130)
        for (i in 0 until 130 * SyntheticScenes.WIDTH) assertEquals(-1, out[i])
        for (i in 130 * SyntheticScenes.WIDTH until out.size) assertEquals(expected[i], out[i])
    }

    @Test
    fun anOutsideMeasureAndBaseAreUsedWhenGiven() {
        val scene = SyntheticScenes.washedOut()
        val src = source(scene, SyntheticScenes.WIDTH)
        val cpu = PhotoLook(SyntheticScenes.WIDTH, SyntheticScenes.HEIGHT).prepare(src)
        var asked = 0
        val look = PhotoLook(SyntheticScenes.WIDTH, SyntheticScenes.HEIGHT)
        val plan = look.prepare(src, measured = {
            asked++
            null
        }, guided = { low, r, e ->
            asked++
            GuidedBase.of(low, r, e)
        })
        assertEquals(2, asked)
        assertSame(cpu.base.a, plan.base.a)
        assertEquals(cpu.saturation, plan.saturation, 0f)
    }

    @Test
    fun aGpuMeasureIsReadLikeTheCpuOne() {
        val low = FloatArray(3 * 2 * 4) { i -> if (i % 4 == 0) -(i / 4).toFloat() else 0.1f * (i % 4) }
        val histogram = IntArray(PhotoLook.BINS).also {
            it[5] = 7
            it[1000] = -1
        } // 2^32 − 1 read as unsigned.
        val m = PhotoLook.Measured.of(3, 2, low, histogram)
        assertArrayEquals(floatArrayOf(0f, -1f, -2f, -3f, -4f, -5f), m.low.data, 0f)
        assertEquals(7L, m.histogram[5])
        assertEquals(0xFFFFFFFFL, m.histogram[1000])
        assertEquals(PhotoLook.saturationOf(0.1f, 0.2f, 0.3f).toDouble(), m.meanSaturation.toDouble(), 1e-6)
    }
}
