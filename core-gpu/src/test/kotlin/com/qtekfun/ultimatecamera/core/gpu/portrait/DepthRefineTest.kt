package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthRefineTest {
    private val w = 320
    private val h = 240
    private val near = 1.0f
    private val far = 0.25f

    /** The subject of the synthetic scene: a bright rectangle on a dark background. */
    private fun subject(x: Int, y: Int) = x in 100..219 && y in 60..179

    /** Red subject, green background (different colours, not only different brightness). */
    private fun guide(): RgbTile {
        val n = w * h
        val subjectAt = BooleanArray(n) { subject(it % w, it / w) }
        return RgbTile(
            w,
            h,
            Plane(w, h, FloatArray(n) { if (subjectAt[it]) 0.7f else 0.2f }),
            Plane(w, h, FloatArray(n) { if (subjectAt[it]) 0.2f else 0.45f }),
            Plane(w, h, FloatArray(n) { 0.2f })
        )
    }

    private fun grey(width: Int, height: Int, value: (Int) -> Float): RgbTile {
        val values = FloatArray(width * height) { value(it % width) }
        return RgbTile(width, height, Plane(width, height, values.copyOf()), Plane(width, height, values.copyOf()), Plane(width, height, values.copyOf()))
    }

    /**
     * What a weak depth source gives: the subject's depth bled [bleed] pixels over its outline (block matching and ToF
     * both do this), [holeRate] of the pixels without depth, and a 12 x 12 hole in the background.
     */
    private fun weakDepth(bleed: Int = 4, holeRate: Double = 0.4, seed: Long = 7): Plane {
        val random = Random(seed)
        return Plane(w, h, FloatArray(w * h) { i -> weakSample(i % w, i / w, bleed, random.nextDouble() < holeRate) })
    }

    private fun weakSample(x: Int, y: Int, bleed: Int, hole: Boolean): Float = when {
        hole -> Bokeh.INVALID
        x in 20..31 && y in 20..31 -> Bokeh.INVALID
        x in 100 - bleed..219 + bleed && y in 60 - bleed..179 + bleed -> near
        else -> far
    }

    private fun inBand(d: Float, band: Float) = d > Bokeh.INVALID && abs(d - near) <= band

    @Test
    fun weakDepthIsRefinedAndTheSubjectSnapsToThePhotosOutline() {
        val depth = weakDepth()
        val band = DepthRefine.band(2.8f, w)
        val result = DepthRefine.refine(depth, guide(), focus = near, fNumber = 2.8f)
        assertEquals(DepthRefine.Mode.REFINED, result.mode)
        assertEquals(0.6f, result.coverage, 0.02f)
        // The ring the depth bled over is background in the photo: refined, it leaves the focus band...
        val pixels = (0 until w * h).map { it % w to it / w }
        val wrong = pixels.count { (x, y) -> inBand(result.depth[x, y], band) != subject(x, y) }
        val ringPixels = pixels.filter { (x, y) -> !subject(x, y) && x in 96..223 && y in 56..183 }
        val ring = ringPixels.size
        val ringFixed = ringPixels.count { (x, y) -> result.depth[x, y] > Bokeh.INVALID && !inBand(result.depth[x, y], band) }
        val ringRaw = ringPixels.count { (x, y) -> inBand(depth[x, y], band) }
        assertTrue("the measured map has the ring in focus ($ringRaw of $ring)", ringRaw > ring / 2)
        assertTrue("refined ring back to the background: $ringFixed of $ring", ringFixed >= ring * 0.95)
        // ...and every hole of the subject and the background is filled from its own side.
        assertTrue("misclassified $wrong of ${w * h}", wrong < w * h / 500)
        assertEquals(far, result.depth[25, 25], 0f)
        for (y in 60..179) for (x in 100..219) assertEquals(near, result.depth[x, y], 0f)
    }

    @Test
    fun holesFillFromTheirOwnSideOfAColourEdgeNotTheNearestPixel() {
        // Left dark at 0.25 D, right bright at 1 D, edge at x = 50; a hole from x = 40 to 54 (asymmetric around it).
        val gw = 100
        val guide = grey(gw, 10) { x -> if (x >= 50) 0.8f else 0.2f }
        val depth = Plane(
            gw,
            10,
            FloatArray(gw * 10) { i ->
                if (i % gw in 40..54) {
                    Bokeh.INVALID
                } else if (i % gw >= 50) {
                    near
                } else {
                    far
                }
            }
        )
        val filled = DepthRefine.fill(depth, guide, reach = 50f)
        // A plain nearest-pixel fill would give x = 48, 49 the right side's depth (nearer to x = 55 than to 39).
        for (i in 0 until gw * 10) {
            val x = i % gw
            if (x in 40..49) assertEquals("x=$x", far, filled.data[i], 0f)
            if (x in 50..54) assertEquals("x=$x", near, filled.data[i], 0f)
        }
    }

    @Test
    fun holesBeyondTheReachStayWithoutDepth() {
        val guide = grey(60, 1) { 0.5f }
        val depth = Plane(60, 1).apply { for (x in 0 until 60) this[x, 0] = if (x == 0) far else Bokeh.INVALID }
        val filled = DepthRefine.fill(depth, guide, reach = 10f)
        for (x in 1..10) assertEquals(far, filled[x, 0], 0f)
        for (x in 11 until 60) assertEquals(Bokeh.INVALID, filled[x, 0], 0f)
    }

    @Test
    fun tooLittleDepthIsLeftAloneSoNothingIsBlurredByGuesswork() {
        val depth = weakDepth(holeRate = 0.9)
        val result = DepthRefine.refine(depth, guide(), focus = near, fNumber = 2.8f)
        assertEquals(DepthRefine.Mode.TOO_SPARSE, result.mode)
        assertSame(depth, result.depth)
        // The holes keep no depth: the blur leaves them sharp.
        val hole = (0 until w * h).first { depth.data[it] <= Bokeh.INVALID }
        assertEquals(0f, Bokeh.cocPx(result.depth.data[hole], near, 1.4f, 1024), 0f)
    }

    @Test
    fun noFocusMeansNoRefinement() {
        val depth = weakDepth()
        val result = DepthRefine.refine(depth, guide(), focus = Bokeh.INVALID, fNumber = 2.8f)
        assertEquals(DepthRefine.Mode.TOO_SPARSE, result.mode)
        assertSame(depth, result.depth)
    }

    @Test
    fun wellCoveredDepthIsUsedAsMeasured() {
        val depth = weakDepth(holeRate = 0.02)
        val result = DepthRefine.refine(depth, guide(), focus = near, fNumber = 2.8f)
        assertEquals(DepthRefine.Mode.MEASURED, result.mode)
        assertSame(depth, result.depth)
        assertTrue(result.describe(), result.describe().startsWith("measured, coverage 9"))
    }

    @Test
    fun theFocusBandFollowsTheApertureWithAFloor() {
        assertTrue(DepthRefine.band(16f, 640) > DepthRefine.band(1.4f, 640))
        assertEquals(DepthRefine.MIN_BAND, DepthRefine.band(1.4f, 4000), 0f)
        assertEquals(Float.MAX_VALUE, DepthRefine.band(0f, 640), 0f)
    }

    @Test
    fun coverageCountsPixelsWithDepth() {
        val depth = Plane(4, 1, floatArrayOf(0.5f, Bokeh.INVALID, 0f, Bokeh.INVALID))
        assertEquals(0.5f, DepthRefine.coverage(depth), 0f)
        assertEquals(0f, DepthRefine.coverage(Plane(0, 0)), 0f)
    }

    @Test
    fun theSubjectMaskFollowsColourWhereTheDepthBledButTrustsSureDepth() {
        val depth = weakDepth(holeRate = 0.0)
        val band = DepthRefine.band(2.8f, w)
        val mask = DepthRefine.subjectMask(depth, guide(), near, band)
        // The 4-pixel ring of bled depth is green like the background: background.
        for (x in 96..99) assertTrue("x=$x", !mask[120 * w + x])
        for (x in 100..104) assertTrue("x=$x", mask[120 * w + x])
        // Far from any outline the depth decides alone, whatever the colour.
        assertTrue(mask[120 * w + 160])
        assertTrue(!mask[5 * w + 5])
        assertEquals(6, DepthRefine.margin(w, h))
        assertEquals(2, DepthRefine.margin(40, 30))
    }

    @Test
    fun withOnlyOneClassNearbyTheDepthDecides() {
        // All in focus: no background anywhere, nothing to compare colours with.
        val depth = Plane(30, 30).apply { data.fill(near) }
        val mask = DepthRefine.subjectMask(depth, grey(30, 30) { x -> x / 30f }, near, 0.1f)
        assertTrue(mask.all { it })
    }

    @Test
    fun theCostHeapPopsCheapestFirstAndGrows() {
        val heap = CostHeap(2)
        val random = Random(3)
        val costs = FloatArray(200) { random.nextFloat() * 100f }
        costs.forEachIndexed { i, c -> heap.push(i, c) }
        var last = -1f
        repeat(costs.size) {
            val c = heap.topCost()
            val i = heap.pop()
            assertEquals(costs[i], c, 0f)
            assertTrue(c >= last)
            last = c
        }
        assertEquals(0, heap.size)
    }
}
