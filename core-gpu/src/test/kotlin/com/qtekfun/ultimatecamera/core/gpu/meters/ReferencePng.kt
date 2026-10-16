package com.qtekfun.ultimatecamera.core.gpu.meters

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Pins meter CPU references to PNGs in `src/test/resources/reference/meters`. Images are in GL row order; the PNG is
 * stored top-down (flipped) so it looks like the screen when reviewed.
 *
 * Regenerate after an intended change:
 * `UPDATE_REFERENCES=1 ./gradlew :core-gpu:testDebugUnitTest --tests '*MeterReferenceImageTest*'` and review the PNG diff.
 */
internal object ReferencePng {
    private const val DIR = "reference/meters"

    fun check(name: String, pixels: IntArray, width: Int, height: Int) {
        val flipped = flip(pixels, width, height)
        if (System.getenv("UPDATE_REFERENCES") == "1") {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, flipped, 0, width) }
            File("src/test/resources/$DIR").mkdirs()
            ImageIO.write(image, "png", File("src/test/resources/$DIR/$name"))
            assumeTrue("reference $name regenerated", false)
        }
        val stream = javaClass.getResourceAsStream("/$DIR/$name") ?: error("missing reference image $name (see ReferencePng)")
        val image = stream.use { ImageIO.read(it) }
        assertTrue("$name: size ${image.width}x${image.height}, expected ${width}x$height", image.width == width && image.height == height)
        val reference = image.getRGB(0, 0, width, height, null, 0, width)
        var worst = 0
        for (i in reference.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                worst = max(worst, abs(((reference[i] shr shift) and 0xFF) - ((flipped[i] shr shift) and 0xFF)))
            }
        }
        assertTrue("$name differs from reference by $worst/255", worst <= 1)
    }

    private fun flip(pixels: IntArray, width: Int, height: Int) = IntArray(pixels.size) { i ->
        pixels[(height - 1 - i / width) * width + i % width]
    }
}
