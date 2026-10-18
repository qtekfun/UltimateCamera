package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D92: the whole portrait blur is one kernel call ([PortraitKernels.blur]). Its CPU reference must be the capture's
 * steps as they were (reduced CoC, gather, edge mask, composite), and a GPU blur that stops part-way must be finished
 * on the CPU from the first row it did not write, never blurring a row twice.
 */
class PortraitBlurTest {
    private val w = 96
    private val h = 64

    /** A textured photo, its half-size copy and a depth map: a near subject on the left, far background, a hole. */
    private fun job(): Pair<BlurJob, IntArray> {
        val random = Random(5)
        val full = IntArray(w * h) { i -> 0xFF000000.toInt() or (random.nextInt(0x1000000)) }
        val sw = w / 2
        val sh = h / 2
        val smallArgb = IntArray(sw * sh) { i -> full[(i / sw) * 2 * w + (i % sw) * 2] }
        val small = RgbTile(sw, sh)
        val luma = Plane(sw, sh)
        for (i in smallArgb.indices) {
            small.r.data[i] = Argb.r(smallArgb[i])
            small.g.data[i] = Argb.g(smallArgb[i])
            small.b.data[i] = Argb.b(smallArgb[i])
            luma.data[i] = Argb.luma(smallArgb[i])
        }
        val depth = Plane(
            24,
            16,
            FloatArray(24 * 16) { i ->
                if (i % 24 in 10..11) {
                    Bokeh.INVALID
                } else if (i % 24 < 10) {
                    2f
                } else {
                    0.2f
                }
            }
        )
        val guide = GuidedUpsample(depth, luma.reduced(24, 16))
        val focus = Bokeh.focusAt(depth, 0.2f, 0.5f)
        return BlurJob(small, smallArgb, luma, guide, focus, 1.4f) to full
    }

    /** The capture's steps before D92, written out: the reference the new kernel call must reproduce bit for bit. */
    private fun before(job: BlurJob, full: IntArray): IntArray {
        val coc = Plane(job.small.width, job.small.height)
        for (y in 0 until coc.height) {
            for (x in 0 until coc.width) {
                val inverse = job.guide.at((x + 0.5f) / coc.width, (y + 0.5f) / coc.height, job.luma[x, y])
                coc[x, y] = Bokeh.cocPx(inverse, job.focus, job.fNumber, coc.width)
            }
        }
        val blurred = DepthBlur.gather(job.small, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS)
        val edges = CompositePlan.edgeMask(coc)
        val scale = w.toFloat() / coc.width
        val out = full.copyOf()
        DepthBlur.composite(out, 0, w, h, blurred) { nx, ny, l ->
            val lx = (nx * coc.width).toInt().coerceIn(0, coc.width - 1)
            val ly = (ny * coc.height).toInt().coerceIn(0, coc.height - 1)
            if (edges[ly * coc.width + lx]) Bokeh.cocPx(job.guide.at(nx, ny, l), job.focus, job.fNumber, w) else DepthBlur.bilinear(coc, nx, ny) * scale
        }
        return out
    }

    @Test
    fun cpuBlurIsTheCapturesStepsAsTheyWere() {
        val (job, full) = job()
        val image = ArrayBands(w, h, full.copyOf())
        CpuPortraitKernels.blur(job, image)
        val expected = before(job, full)
        assertArrayEquals(expected, image.pixels)
        // The scene blurs something and keeps the subject.
        assertNotEquals(full[10 * w + 90], image.pixels[10 * w + 90])
        assertEquals(full[32 * w + 8], image.pixels[32 * w + 8])
    }

    @Test
    fun rowsAboveTheStartRowAreLeftAsTheyAre() {
        val (job, full) = job()
        val image = ArrayBands(w, h, full.copyOf())
        CpuPortraitKernels.blur(job, image, fromRow = 20)
        val expected = before(job, full)
        for (i in 0 until 20 * w) assertEquals(full[i], image.pixels[i])
        for (i in 20 * w until w * h) assertEquals(expected[i], image.pixels[i])
    }

    /** Writes the reference's first [rows] rows like the GPU's bands, then fails like a lost context. */
    private class FailingGpu(private val rows: Int) : PortraitKernels {
        override val name = "gpu"

        override fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel, maxRadius: Float): RgbTile = error("not used")

        override fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int): Plane = error("not used")

        override fun blur(job: BlurJob, image: ArgbBands, fromRow: Int) {
            val copy = IntArray(image.width * image.height)
            image.read(0, image.height, copy)
            val done = ArrayBands(image.width, image.height, copy)
            CpuPortraitKernels.blur(job, done)
            val band = IntArray(image.width * rows)
            done.read(0, rows, band)
            image.write(0, rows, band)
            error("context lost")
        }
    }

    @Test
    fun aGpuBlurThatStopsPartWayIsFinishedOnTheCpuWithoutBlurringARowTwice() {
        val (job, full) = job()
        val health = KernelHealth()
        val image = ArrayBands(w, h, full.copyOf())
        FallbackPortraitKernels(health, { FailingGpu(rows = 24) }).use { it.blur(job, image) }
        assertArrayEquals(before(job, full), image.pixels)
        assertTrue(health.failure.orEmpty(), health.failure.orEmpty().startsWith("blur: "))
    }

    @Test
    fun greyPngIsAStandardFile() {
        val grey = ByteArray(7 * 3) { i -> (i * 12).toByte() }
        val out = ByteArrayOutputStream()
        GreyPng.write(grey, 7, 3, out)
        val image = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(7, image.width)
        assertEquals(3, image.height)
        for (i in grey.indices) assertEquals(grey[i].toInt() and 0xFF, image.raster.getSample(i % 7, i / 7, 0))
    }

    @Test
    fun depthPng16IsAStandardFileWithTheUpFilter() {
        val depth = Plane(5, 4, FloatArray(20) { i -> if (i == 3) Bokeh.INVALID else 0.1f + i * 0.05f })
        val out = ByteArrayOutputStream()
        DepthPng16.write(depth, DepthPng16.Meta(0, 2f, 0.5f, 0.5f), out)
        val bytes = out.toByteArray()
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(5, image.width)
        assertEquals(0, image.raster.getSample(3, 0, 0))
        assertEquals(65535, image.raster.getSample(4, 3, 0))
        val decoded = DepthPng16.read(ByteArrayInputStream(bytes))
        for (i in 0 until 20) assertEquals(depth.data[i], decoded.depth.data[i], DepthPng16.quantum(0.95f) + 1e-6f)
    }
}
