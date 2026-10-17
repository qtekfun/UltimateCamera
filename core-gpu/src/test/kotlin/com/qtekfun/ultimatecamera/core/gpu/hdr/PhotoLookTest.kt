package com.qtekfun.ultimatecamera.core.gpu.hdr

import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's comparison on his phone (v0.6.1, same street scene): our HDR Auto and Ultra HDR photos had lifted blacks
 * and less contrast than the phone's own camera (luma p1 32 vs 13, sd 56 vs 63). Fixtures: 400-px copies of the real
 * shots, metadata stripped (`src/test/resources/photo-look`).
 */
class PhotoLookTest {
    private class Image(val width: Int, val height: Int, val rgb: IntArray)

    private fun fixture(name: String): Image {
        val img = requireNotNull(ImageIO.read(javaClass.getResource("/photo-look/$name.jpg"))) { name }
        val rgb = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, rgb, 0, img.width)
        return Image(img.width, img.height, rgb)
    }

    private fun stats(image: Image): LumaStats = LumaStats.Builder().apply { image.rgb.forEach(::add) }.build()

    private fun look(image: Image, params: RenderParams = RenderParams.BASE): LumaStats {
        val source = RowSource { y, r, g, b ->
            for (x in 0 until image.width) {
                val p = image.rgb[y * image.width + x]
                r[x] = ((p shr 16) and 0xFF) / 255f
                g[x] = ((p shr 8) and 0xFF) / 255f
                b[x] = (p and 0xFF) / 255f
            }
        }
        val builder = LumaStats.Builder()
        val out = IntArray(image.width * image.height)
        PhotoLook(image.width, image.height, params).apply(source, { y0, rows, r, g, b, _ ->
            for (i in 0 until rows * image.width) out[y0 * image.width + i] = (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
        })
        out.forEach(builder::add)
        return builder.build()
    }

    private fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    @Test
    fun fixturesReproduceTheOwnersMeasurements() {
        val native = stats(fixture("native"))
        val ours = stats(fixture("ours-auto"))
        assertTrue("native $native", native.p1 in 10..16 && native.sd > 60)
        assertTrue("ours $ours", ours.p1 in 28..36 && ours.sd < 58)
    }

    /** Our rendering of [ours] must land near the phone's own [native] shot of the same scene. */
    private fun assertNearNative(native: String, ours: String) {
        val n = stats(fixture(native))
        val s = look(fixture(ours))
        println("PHOTO_LOOK " + ours + ": " + s + " / native " + n)
        assertTrue("p1: " + s, s.p1 <= 22)
        assertTrue("p99: " + s, s.p99 <= 247)
        assertTrue("clipped: " + s, s.clipped < 0.01)
        assertEquals("saturation " + s + " vs " + n, n.saturation, s.saturation, n.saturation * 0.15)
        assertEquals("contrast " + s + " vs " + n, n.sd, s.sd, n.sd * 0.15)
    }

    @Test
    fun hdrAutoLandsNearTheNativeCamera() = assertNearNative("native", "ours-auto")

    @Test
    fun ultraHdrLandsNearTheNativeCamera() = assertNearNative("native", "ours-ultra")

    @Test
    fun secondSceneLandsNearTheNativeCamera() = assertNearNative("native-2", "ours-2")

    @Test
    fun theOwnersShotsWereMeasuredAsReported() {
        // v0.6.2 pair: saturation was 50-66 % of the phone's own camera, blacks lifted.
        val native = stats(fixture("native-2"))
        val ours = stats(fixture("ours-2"))
        assertTrue("native-2 " + native, native.saturation in 0.15..0.19 && native.p1 in 15..22)
        assertTrue("ours-2 " + ours, ours.saturation in 0.09..0.13 && ours.p1 in 28..36)
    }

    @Test
    fun dullScenesGetMoreSaturationThanColourfulOnes() {
        val p = RenderParams.BASE
        assertTrue(PhotoLook.saturationGain(0.05f, p) > PhotoLook.saturationGain(0.12f, p))
        assertEquals(1f, PhotoLook.saturationGain(0.5f, p), 0f)
        assertEquals(p.saturationMax, PhotoLook.saturationGain(0f, p), 0f)
    }

    @Test
    fun skinAndSkyGetLessBoost() {
        val p = RenderParams.BASE
        assertEquals(1.25f, PhotoLook.protectedGain(0.8f, 0.6f, 0.45f, 1.5f, p), 1e-6f) // skin
        assertEquals(1.35f, PhotoLook.protectedGain(0.55f, 0.7f, 0.9f, 1.5f, p), 1e-6f) // sky
        assertEquals(1.5f, PhotoLook.protectedGain(0.2f, 0.6f, 0.2f, 1.5f, p), 0f) // foliage
    }

    @Test
    fun moreContrastRaisesTheSpreadWithoutClipping() {
        val base = look(fixture("ours-auto"))
        val stronger = look(fixture("ours-auto"), RenderParams.BASE.copy(contrast = 0.5f, clarity = 0.35f))
        assertTrue("stronger vs base: " + stronger + " / " + base, stronger.sd > base.sd)
        assertTrue("stronger: " + stronger, stronger.clipped < 0.01)
    }

    @Test
    fun toneCurveIsMonotonicKeepsTheEndsAndRollsOffHighlights() {
        for (contrast in listOf(RenderParams.BASE.contrast, 0.5f, 1f)) {
            var previous = -1f
            for (i in 0..1000) {
                val v = ToneCurve.s(i / 1000f, contrast)
                assertTrue("contrast " + contrast + " at " + i, v >= previous)
                previous = v
            }
            assertEquals(0f, ToneCurve.s(0f, contrast), 0f)
            assertEquals(1f, ToneCurve.s(1f, contrast), 1e-6f)
            // Highlight shoulder: slope below 1 near white, so bright detail is compressed, never pushed into clipping.
            val slope = (ToneCurve.s(1f, contrast) - ToneCurve.s(0.98f, contrast)) / 0.02f
            assertTrue("contrast " + contrast + " slope " + slope, slope < 1f)
            assertTrue(ToneCurve.s(0.95f, contrast) < 1f)
        }
    }

    @Test
    fun levelsMapTheBlackPointToZero() {
        assertEquals(0f, ToneCurve.levels(0.1f, 0.1f), 0f)
        assertEquals(1f, ToneCurve.levels(1f, 0.1f), 1e-6f)
    }

    @Test
    fun aPhotoWithDeepBlacksAlreadyKeepsItsBlackPoint() {
        val w = 64
        val h = 48
        val source = RowSource { y, r, g, b ->
            for (x in 0 until w) {
                val v = x / (w - 1f)
                r[x] = v
                g[x] = v
                b[x] = v
            }
        }
        val look = PhotoLook(w, h)
        look.apply(source, { _, _, _, _, _, _ -> })
        assertEquals(0f, look.blackPoint, 0.01f)
    }
}
