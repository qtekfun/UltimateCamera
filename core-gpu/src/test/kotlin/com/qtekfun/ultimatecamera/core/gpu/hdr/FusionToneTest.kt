package com.qtekfun.ultimatecamera.core.gpu.hdr

import javax.imageio.ImageIO
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's fusion shot (v0.6.2): the sky was recovered but the photo was about 1.2 stops dark and highlights never
 * reached white (p50 78, p99 205; the phone's own camera of the same scene: p50 152, p99 238). Fixture: a 400-px copy,
 * metadata stripped.
 */
class FusionToneTest {
    private val image = requireNotNull(ImageIO.read(javaClass.getResource("/photo-look/ours-fusion.jpg")))
    private val rgb = IntArray(image.width * image.height).also { image.getRGB(0, 0, image.width, image.height, it, 0, image.width) }

    private fun luma(p: Int) = PhotoLook.luma(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f)

    private fun percentile(values: IntArray, q: Double): Int {
        val l = values.map { (luma(it) * 255f + 0.5f).toInt() }.sorted()
        return l[(l.size * q).toInt().coerceAtMost(l.size - 1)]
    }

    private fun sd(values: List<Float>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    /** The 0 EV frame of such a scene has its median near the phone's own rendering (≈ 145 of 255). */
    private val tone: FusionTone by lazy {
        val plane = Plane(image.width, image.height, FloatArray(rgb.size) { luma(rgb[it]) })
        val sorted = plane.data.copyOf().also { it.sort() }
        FusionTone.of(sorted[sorted.size / 2], sorted[(sorted.size * 0.99).toInt()], referenceMedian = 0.57f)
    }

    @Test
    fun theFixtureIsTheDarkFusionTheOwnerReported() {
        assertTrue(percentile(rgb, 0.5) in 70..86)
        assertTrue(percentile(rgb, 0.99) in 198..212)
    }

    @Test
    fun mediansComeUpToTheZeroEvExposureAndHighlightsReachNearWhite() {
        val toned = rgb.copyOf().also { tone.apply(it) }
        val p50 = percentile(toned, 0.5)
        val p99 = percentile(toned, 0.99)
        val clipped = toned.count { (luma(it) * 255f) >= 253.5f }.toDouble() / toned.size
        assertTrue("p50 $p50", p50 in 140..155)
        assertTrue("p99 $p99", p99 in 235..245)
        assertTrue("clipped $clipped", clipped < 0.01)
    }

    @Test
    fun theRecoveredSkyKeepsItsDetail() {
        val sky = (0 until image.width * image.height / 4) // top quarter: the sky
        val before = sky.map { luma(rgb[it]) }
        val toned = rgb.copyOf().also { tone.apply(it) }
        val after = sky.map { luma(toned[it]) }
        assertTrue("sky detail ${sd(after)} vs ${sd(before)}", sd(after) >= 0.7 * sd(before))
        assertTrue("sky not clipped", after.count { it >= 0.995f } < after.size / 100)
    }

    @Test
    fun curveIsMonotonicKeepsTheEndsAndNeverDarkens() {
        for (i in 1 until 256) assertTrue("at $i", tone[i] >= tone[i - 1])
        assertEquals(0, tone[0])
        assertEquals(255, tone[255])
        for (i in 0 until 256) assertTrue("darkens at $i", tone[i] >= i)
        // A fusion already as bright as its 0 EV frame and reaching white is left alone.
        val bright = FusionTone.of(median = 0.55f, p99 = 0.96f, referenceMedian = 0.5f)
        for (i in 0 until 256) assertEquals(i.toFloat(), bright[i].toFloat(), 1f)
    }

    @Test
    fun targetMedianStaysInItsRange() {
        val veryDarkReference = FusionTone.of(median = 0.2f, p99 = 0.7f, referenceMedian = 0.1f)
        assertEquals((FusionTone.MIN_TARGET * 255).toInt().toFloat(), veryDarkReference[(0.2f * 255).toInt()].toFloat(), 3f)
    }
}
