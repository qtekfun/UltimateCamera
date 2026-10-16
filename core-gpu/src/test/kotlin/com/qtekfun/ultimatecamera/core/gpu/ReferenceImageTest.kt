package com.qtekfun.ultimatecamera.core.gpu

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pins the LUT maths to reference images in `src/test/resources/reference`. The on-device shader test compares the GPU
 * output with the same maths, so a change in either shows up here or there.
 *
 * To regenerate after an intended change: `UPDATE_REFERENCES=1 ./gradlew :core-gpu:testDebugUnitTest --tests '*ReferenceImageTest*'`
 * and review the PNG diff in the PR.
 */
class ReferenceImageTest {
    @Test
    fun syntheticGradientMatchesReference() = check("gradient.png", ReferenceImages.gradient())

    @Test
    fun creativeLutOnGradientMatchesReference() = check("creative33_gradient.png", LutMath.applyToArgb(SpikeLuts.creative(33), ReferenceImages.gradient()))

    /** The gradient read as an HLG camera signal and encoded to UC-Log (the GLSL stage computes the same). */
    @Test
    fun ucLogFromHlgOnGradientMatchesReference() = check("uclog_hlg_gradient.png", UcLog.encodeArgb(LogSource.HLG, ReferenceImages.gradient()))

    /** The gradient read as UC-Log and converted with the default Rec.709 LUT. */
    @Test
    fun ucLogToRec709LutOnGradientMatchesReference() =
        check("uclog_rec709_33_gradient.png", LutMath.applyToArgb(UcLogLuts.toRec709(33), ReferenceImages.gradient()))

    @Test
    fun identityLutIsLossless() {
        val input = ReferenceImages.gradient()
        val output = LutMath.applyToArgb(CubeLut.identity(33), input)
        assertTrue("identity LUT changed pixels", maxChannelDiff(input, output) == 0)
    }

    private fun check(name: String, pixels: IntArray) {
        val w = ReferenceImages.WIDTH
        val h = ReferenceImages.HEIGHT
        if (System.getenv("UPDATE_REFERENCES") == "1") {
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, pixels, 0, w) }
            ImageIO.write(image, "png", File("src/test/resources/reference/$name"))
            assumeTrue("reference $name regenerated", false)
        }
        val stream = javaClass.getResourceAsStream("/reference/$name") ?: error("missing reference image $name (see class doc)")
        val reference = stream.use { ImageIO.read(it) }.getRGB(0, 0, w, h, null, 0, w)
        val diff = maxChannelDiff(reference, pixels)
        assertTrue("$name differs from reference by $diff/255", diff <= 1)
    }

    private fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                worst = max(worst, abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
            }
        }
        return worst
    }
}
