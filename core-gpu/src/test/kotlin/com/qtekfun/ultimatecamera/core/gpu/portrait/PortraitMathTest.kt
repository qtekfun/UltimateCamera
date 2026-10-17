package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitMathTest {
    @Test
    fun cocFollowsTheThinLensFormula() {
        // 50 mm at f/2, focus at 2 m (0.5 D), background at infinity (0 D): f²/N·Δ = 0.0025/2·0.5 = 0.625 mm on 36 mm.
        val coc = Bokeh.cocPx(inverse = 0f, focusInverse = 0.5f, fNumber = 2f, widthPx = 3600)
        assertEquals(62.5f, coc, 0.01f)
    }

    @Test
    fun cocIsZeroInFocusWhenOffAndWithoutDepth() {
        assertEquals(0f, Bokeh.cocPx(0.5f, 0.5f, 1.4f, 4000), 0f)
        assertEquals(0f, Bokeh.cocPx(0f, 0.5f, 0f, 4000), 0f)
        assertEquals(0f, Bokeh.cocPx(Bokeh.INVALID, 0.5f, 1.4f, 4000), 0f)
    }

    @Test
    fun cocGrowsWithWiderApertureAndDistanceFromFocus() {
        val wide = Bokeh.cocPx(0.1f, 1f, 1.4f, 4000)
        val narrow = Bokeh.cocPx(0.1f, 1f, 16f, 4000)
        assertTrue(wide > narrow * 10f)
        assertTrue(Bokeh.cocPx(0f, 1f, 2f, 4000) > Bokeh.cocPx(0.5f, 1f, 2f, 4000))
        // Linear in inverse depth: the same dioptre gap in front and behind blurs the same.
        assertEquals(Bokeh.cocPx(0.5f, 1f, 2f, 4000), Bokeh.cocPx(1.5f, 1f, 2f, 4000), 1e-3f)
    }

    @Test
    fun sliderPositionZeroIsOffThenTheStops() {
        assertEquals(0f, Bokeh.fNumber(0), 0f)
        assertEquals(1.4f, Bokeh.fNumber(1), 0f)
        assertEquals(16f, Bokeh.fNumber(Bokeh.STOPS.size), 0f)
    }

    @Test
    fun focusIsTheMedianDepthAroundThePointIgnoringHoles() {
        val depth = Plane(9, 9).apply { data.fill(0.2f) }
        for (y in 2..6) for (x in 2..6) depth[x, y] = 1.5f
        depth[4, 4] = Bokeh.INVALID
        depth[3, 4] = 9f // A single outlier does not move a median.
        assertEquals(1.5f, Bokeh.focusInverseDepth(depth, 0.5f, 0.5f, radius = 2), 0f)
        assertEquals(Bokeh.INVALID, Bokeh.focusInverseDepth(Plane(3, 3).apply { data.fill(Bokeh.INVALID) }, 0.5f, 0.5f), 0f)
    }

    /** A 64 x 32 guide with a hard edge at x = 26 (not on the 4-pixel grid of the reduced depth). */
    private val edge = 26
    private val guide = Plane(64, 32).apply { for (y in 0 until 32) for (x in 0 until 64) this[x, y] = if (x < edge) 0.1f else 0.9f }

    /** The true depth: near subject (2 D) left of the edge, far wall (0.2 D) right of it, reduced 4x. */
    private val truth = Plane(64, 32).apply { for (y in 0 until 32) for (x in 0 until 64) this[x, y] = if (x < edge) 2f else 0.2f }

    @Test
    fun guidedUpsamplingKeepsTheColourEdge() {
        val up = GuidedUpsample(truth.reduced(16, 8), guide.reduced(16, 8)).upsample(guide)
        for (y in 0 until 32) {
            assertEquals("subject just left of the edge", 2f, up[edge - 1, y], 0.1f)
            assertEquals("wall just right of the edge", 0.2f, up[edge, y], 0.1f)
            assertEquals(2f, up[2, y], 0.01f)
            assertEquals(0.2f, up[60, y], 0.01f)
        }
    }

    @Test
    fun plainBilinearWouldBleedAcrossTheEdge() {
        // The reference the guided version beats: without the guide the pixels at the edge are a mix.
        val low = truth.reduced(16, 8)
        val bilinear = DepthBlur.bilinear(low, (edge + 0.5f) / 64f, 0.5f)
        assertTrue(abs(bilinear - 0.2f) > 0.3f)
    }

    @Test
    fun guidedUpsamplingSkipsHolesAndReportsNoDepthWhereThereIsNone() {
        val low = truth.reduced(16, 8)
        low[2, 2] = Bokeh.INVALID
        val up = GuidedUpsample(low, guide.reduced(16, 8))
        assertEquals(2f, up.at(2.5f / 16f, 2.5f / 8f, 0.1f), 0.05f)
        val empty = Plane(4, 4).apply { data.fill(Bokeh.INVALID) }
        assertEquals(Bokeh.INVALID, GuidedUpsample(empty, Plane(4, 4)).at(0.5f, 0.5f, 0f), 0f)
    }

    @Test
    fun gatherKeepsInFocusPixelsAndDoesNotSmearTheSubjectIntoTheBackground() {
        // Bright sharp subject (coc 0) at x < 10, dark background blurred hard (coc 16) to the right.
        val w = 32
        val h = 16
        val image = RgbTile(w, h)
        val coc = Plane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < 10) {
                    1f
                } else if ((x + y) % 2 == 0) {
                    0.2f
                } else {
                    0f
                }
                image.r[x, y] = v
                image.g[x, y] = v
                image.b[x, y] = v
                coc[x, y] = if (x < 10) 0f else 16f
            }
        }
        val out = DepthBlur.gather(image, coc)
        for (y in 0 until h) {
            for (x in 0 until 10) assertEquals(1f, out.r[x, y], 0f)
            // Next to the subject the background stays dark: the subject's samples do not reach it.
            assertTrue("x=10,y=$y ${out.r[10, y]}", out.r[10, y] < 0.25f)
            // The checkerboard is blurred towards its mean.
            assertEquals(0.1f, out.r[24, y], 0.04f)
        }
    }

    @Test
    fun compositeLeavesSharpPixelsAndBlendsBlurredOnes() {
        val blurred = RgbTile(2, 1).apply {
            r.data.fill(0f)
            g.data.fill(0f)
            b.data.fill(1f)
        }
        val white = Argb.pack(1f, 1f, 1f)
        val row = IntArray(8) { white }
        DepthBlur.composite(row, top = 0, width = 8, height = 1, blurred = blurred) { nx, _, _ -> if (nx < 0.5f) 0f else 100f }
        assertArrayEquals(IntArray(4) { white }, row.copyOfRange(0, 4))
        assertArrayEquals(IntArray(4) { Argb.pack(0f, 0f, 1f) }, row.copyOfRange(4, 8))
        assertEquals(0f, DepthBlur.blend(0.5f, 4f), 0f)
        assertEquals(1f, DepthBlur.blend(8f, 4f), 0f)
    }

    @Test
    fun depth16DecodesRangeAndConfidence() {
        assertEquals(1f, Depth16.confidence(1500), 0f)
        assertEquals(0f, Depth16.confidence(1 shl 13 or 1500), 0f)
        assertEquals(6f / 7f, Depth16.confidence(7 shl 13 or 1500), 1e-6f)
        val samples = shortArrayOf(1000, (1 shl 13 or 500).toShort(), 77, 250, 0, 77)
        val inv = Depth16.inverseDepth(samples, width = 2, height = 2, rowStride = 3)
        assertEquals(1f, inv[0, 0], 1e-6f)
        assertEquals("zero confidence", Bokeh.INVALID, inv[1, 0], 0f)
        assertEquals(4f, inv[0, 1], 1e-6f)
        assertEquals("no range", Bokeh.INVALID, inv[1, 1], 0f)
        assertEquals(Bokeh.INVALID, Depth16.inverseDepth(shortArrayOf(0), 1, 1)[0, 0], 0f)
    }

    @Test
    fun blockMatchingFindsTheShiftOfEachHalf() {
        val w = 80
        val h = 40
        val random = Random(7)
        val texture = Plane(w + 20, h).apply { for (i in data.indices) data[i] = random.nextFloat() }
        val main = Plane(w, h)
        val second = Plane(w, h)
        // Left half near (shift 6 px), right half far (shift 1 px), along x.
        for (y in 0 until h) {
            for (x in 0 until w) {
                main[x, y] = texture[x + 10, y]
                val shift = if (x < w / 2) 6 else 1
                second[x, y] = texture[(x + 10 - shift).coerceIn(0, w + 19), y]
            }
        }
        val disparity = StereoDepth.disparity(main, second, maxDisparity = 8)
        for (y in 5 until h - 5) {
            for (x in 10 until w / 2 - 8) assertEquals(6f, disparity[x, y], 0f)
            for (x in w / 2 + 8 until w - 10) assertEquals(1f, disparity[x, y], 0f)
        }
        val inverse = StereoDepth.inverseDepth(disparity, maxDisparity = 8)
        assertTrue(inverse[15, 20] > inverse[60, 20])
    }

    @Test
    fun blockMatchingMarksFlatRegionsAsNoDepth() {
        val flat = Plane(30, 20).apply { data.fill(0.5f) }
        val disparity = StereoDepth.disparity(flat, flat, maxDisparity = 4)
        assertTrue(disparity.data.all { it == Bokeh.INVALID })
    }

    @Test
    fun medianRemovesIsolatedWrongOffsetsAndKeepsEdges() {
        val plane = Plane(10, 6, FloatArray(60) { i -> if (i % 10 < 5) 6f else 1f })
        plane[2, 2] = 16f // A wrong match inside the near half.
        plane[7, 3] = Bokeh.INVALID // A hole inside the far half.
        val out = StereoDepth.median3(plane)
        assertEquals(6f, out[2, 2], 0f)
        assertEquals(1f, out[7, 3], 0f)
        for (y in 0 until 6) {
            assertEquals(6f, out[4, y], 0f)
            assertEquals(1f, out[5, y], 0f)
        }
        val holes = Plane(3, 3).apply { data.fill(Bokeh.INVALID) }
        holes[1, 1] = 2f
        assertEquals("too few valid neighbours", Bokeh.INVALID, StereoDepth.median3(holes)[1, 1], 0f)
    }

    @Test
    fun tabledGaussiansMatchTheExactWeights() {
        // One low-res sample each side of a hard edge: the tabled weights must give the exact bilateral result.
        val depth = Plane(2, 1, floatArrayOf(2f, 0.2f))
        val guideLow = Plane(2, 1, floatArrayOf(0.1f, 0.9f))
        val at = GuidedUpsample(depth, guideLow).at(0.4f, 0.5f, 0.3f)
        fun w(dx: Float, dg: Float) = kotlin.math.exp(-dx * dx / 2f - dg * dg / (2f * 0.08f * 0.08f))
        val wa = w(0.8f - 0.5f - 0f, 0.2f)
        val wb = w(0.8f - 0.5f - 1f, 0.6f)
        assertEquals((wa * 2f + wb * 0.2f) / (wa + wb), at, 0.01f)
    }

    @Test
    fun compositeTakesACocFieldWithPrimitiveArguments() {
        val field = CocField { nx, ny, luma -> nx + ny + luma }
        assertEquals(1.5f, field.cocAt(0.5f, 0.25f, 0.75f), 0f)
    }

    @Test
    fun depthImageIsWhiteNearBlackFar() {
        val depth = Plane(3, 1, floatArrayOf(2f, 0.2f, Bokeh.INVALID))
        val grey = DepthImage.grey(depth)
        assertEquals(255, grey[0].toInt() and 0xFF)
        assertEquals(0, grey[1].toInt() and 0xFF)
        assertEquals(0, grey[2].toInt() and 0xFF)
    }
}
