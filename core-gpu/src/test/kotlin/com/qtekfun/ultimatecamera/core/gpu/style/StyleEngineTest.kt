package com.qtekfun.ultimatecamera.core.gpu.style

import com.qtekfun.ultimatecamera.core.gpu.LutMath
import com.qtekfun.ultimatecamera.core.gpu.UcLogLuts
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleEngineTest {
    private val out = FloatArray(3)

    /** Every preset at its default, at the pad's corners and at half intensity. */
    private val variants: List<StyleParams> = StylePreset.entries.flatMap { preset ->
        listOf(
            StyleParams.of(preset),
            StyleParams(preset, tone = 1f, warmth = 1f, saturation = 1f),
            StyleParams(preset, tone = -1f, warmth = -1f, saturation = -1f),
            StyleParams(preset, tone = 1f, warmth = -1f, saturation = 1f, intensity = 0.5f)
        )
    }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test
    fun intensityZeroIsTheIdentityForEveryPreset() {
        val random = Random(1)
        for (preset in StylePreset.entries) {
            val p = StyleParams(preset, tone = 0.7f, warmth = -0.4f, saturation = 0.9f, intensity = 0f)
            assertTrue(p.isIdentity)
            repeat(200) {
                val r = random.nextFloat()
                val g = random.nextFloat()
                val b = random.nextFloat()
                StyleEngine.transform(p, r, g, b, out)
                assertArrayEquals(floatArrayOf(r, g, b), out, 0f)
            }
        }
    }

    @Test
    fun standardAtTheCentreIsTheIdentityAndItsLutIsTooWithinOneCode() {
        val p = StyleParams.of(StylePreset.STANDARD)
        assertTrue(p.isIdentity)
        val lut = StyleEngine.lutFor(p)
        val random = Random(3)
        repeat(500) {
            val c = floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat())
            LutMath.apply(lut, c[0], c[1], c[2], out)
            assertArrayEquals(c, out, 1e-5f)
        }
    }

    @Test
    fun outputStaysInGamutForEveryVariant() {
        val random = Random(5)
        for (p in variants) {
            repeat(2_000) {
                StyleEngine.transform(p, random.nextFloat(), random.nextFloat(), random.nextFloat(), out)
                for (v in out) assertTrue("$p gave $v", v in -1e-6f..1.000001f)
            }
            // The cube's corners are the worst case for clipping.
            for (corner in 0 until 8) {
                StyleEngine.transform(p, (corner and 1).toFloat(), (corner shr 1 and 1).toFloat(), (corner shr 2 and 1).toFloat(), out)
                for (v in out) assertTrue("$p corner $corner gave $v", v in -1e-6f..1.000001f)
            }
        }
    }

    @Test
    fun lumaIsMonotonicAlongGreyAndColourRamps() {
        val tints =
            listOf(
                floatArrayOf(1f, 1f, 1f),
                floatArrayOf(1f, 0.2f, 0.1f),
                floatArrayOf(0.1f, 0.8f, 0.2f),
                floatArrayOf(0.2f, 0.3f, 1f),
                floatArrayOf(0.9f, 0.6f, 0.5f)
            )
        for (p in variants) {
            for (tint in tints) {
                var last = -1f
                for (i in 0..255) {
                    val t = i / 255f
                    StyleEngine.transform(p, tint[0] * t, tint[1] * t, tint[2] * t, out)
                    val y = luma(out)
                    assertTrue("$p ramp ${tint.toList()} at $i: $y < $last", y >= last - 1e-5f)
                    last = y
                }
            }
        }
    }

    @Test
    fun toneCurveIsStrictlyIncreasingForTheExtremeContrasts() {
        for (contrast in listOf(-1f, 1f)) {
            val look = StyleLook(contrast = contrast, fade = 0.1f)
            var last = -1f
            for (i in 0..1000) {
                val y = StyleEngine.toneCurve(look, contrast, i / 1000f)
                assertTrue(y > last)
                last = y
            }
        }
    }

    @Test
    fun noGradientIsFlattenedByClipping() {
        // A saturated red ramp under the strongest saturation: neighbouring inputs must stay distinct (no clipped plateau).
        val p = StyleParams(StylePreset.VIBRANT, tone = 1f, saturation = 1f)
        val previous = FloatArray(3)
        StyleEngine.transform(p, 0.5f, 0.05f, 0.05f, previous)
        for (i in 1..50) {
            val r = 0.5f + i / 100f
            StyleEngine.transform(p, r, 0.05f, 0.05f, out)
            assertTrue("red ramp flattened at $r", abs(luma(out) - luma(previous)) > 1e-4f)
            out.copyInto(previous)
        }
    }

    @Test
    fun skinHuesKeepTheirHueUnderSaturationAndWarmth() {
        val skins =
            listOf(floatArrayOf(0.95f, 0.8f, 0.72f), floatArrayOf(0.85f, 0.62f, 0.5f), floatArrayOf(0.55f, 0.38f, 0.28f), floatArrayOf(0.3f, 0.2f, 0.15f))
        val strong = listOf(
            StyleParams(StylePreset.VIBRANT, saturation = 1f),
            StyleParams(StylePreset.WARM, warmth = 1f),
            StyleParams(StylePreset.COOL, warmth = -1f),
            StyleParams(StylePreset.RICH_CONTRAST, tone = 1f, saturation = 1f)
        )
        for (p in strong) {
            for (skin in skins) {
                StyleEngine.transform(p, skin[0], skin[1], skin[2], out)
                val shift = abs(hue(out) - hue(skin))
                assertTrue("$p moved skin ${skin.toList()} by $shift°", shift < 12f)
            }
        }
    }

    @Test
    fun skinIsBoostedLessThanOtherHues() {
        val p = StyleParams(StylePreset.VIBRANT, saturation = 1f)
        val skin = floatArrayOf(0.85f, 0.62f, 0.5f)
        val teal = floatArrayOf(0.5f, 0.68f, 0.75f) // about the same luma and chroma, opposite hue
        assertTrue(gain(p, skin) < gain(p, teal))
    }

    @Test
    fun goldenChartBehaviour() {
        fun styled(preset: StylePreset, r: Float, g: Float, b: Float) = FloatArray(3).also { StyleEngine.transform(StyleParams.of(preset), r, g, b, it) }
        // Contrast pivots on mid grey: 0.5 stays, shadows go down, highlights go up.
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), styled(StylePreset.RICH_CONTRAST, 0.5f, 0.5f, 0.5f), 1e-4f)
        assertTrue(styled(StylePreset.RICH_CONTRAST, 0.2f, 0.2f, 0.2f)[0] < 0.2f)
        assertTrue(styled(StylePreset.RICH_CONTRAST, 0.8f, 0.8f, 0.8f)[0] > 0.8f)
        // Warm and cool tint the greys, blacks and whites stay neutral.
        styled(StylePreset.WARM, 0.5f, 0.5f, 0.5f).let { assertTrue(it[0] > it[2] + 0.02f) }
        styled(StylePreset.COOL, 0.5f, 0.5f, 0.5f).let { assertTrue(it[2] > it[0] + 0.02f) }
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), styled(StylePreset.WARM, 1f, 1f, 1f), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), styled(StylePreset.COOL, 0f, 0f, 0f), 1e-4f)
        // Mono has no colour; the film looks lift black.
        styled(StylePreset.MONO, 0.8f, 0.3f, 0.1f).let { assertEquals(it[0], it[2], 1e-5f) }
        assertTrue(styled(StylePreset.FADED, 0f, 0f, 0f)[0] >= 0.09f)
        assertTrue(styled(StylePreset.SOFT_FILM, 0f, 0f, 0f)[0] > 0.03f)
        // Vibrant enriches a muted blue, Natural mutes it; both keep its luma.
        val muted = floatArrayOf(0.35f, 0.45f, 0.65f)
        assertTrue(gain(StyleParams.of(StylePreset.VIBRANT), muted) > 1.1f)
        assertTrue(gain(StyleParams.of(StylePreset.NATURAL), muted) < 0.95f)
    }

    @Test
    fun lutMatchesTheExactTransformOnAverageWithinOneCode() {
        // Where the gamut fit bends the result (very dark or very bright saturated colours), a 33-point lattice smooths
        // the bend and is up to about a dozen codes off there; on average it is well under one 8-bit code.
        val random = Random(9)
        for (p in variants.filter { !it.isIdentity }) {
            val lut = StyleEngine.lutFor(p)
            val exact = FloatArray(3)
            var sum = 0.0
            var worst = 0f
            repeat(300) {
                val r = random.nextFloat()
                val g = random.nextFloat()
                val b = random.nextFloat()
                LutMath.apply(lut, r, g, b, out)
                StyleEngine.transform(p, r, g, b, exact)
                for (c in 0..2) {
                    val e = abs(exact[c] - out[c])
                    sum += e
                    worst = maxOf(worst, e)
                }
            }
            assertTrue("$p mean ${sum / 900 * 255} codes", sum / 900 < 1.0 / 255)
            assertTrue("$p worst ${worst * 255} codes", worst < 16f / 255f)
        }
    }

    @Test
    fun lutsAreDeterministicAndCached() {
        val p = StyleParams(StylePreset.SOFT_FILM, tone = 0.3f, warmth = -0.2f)
        val a = StyleEngine.lutFor(p)
        assertSame(a, StyleEngine.lutFor(StyleParams.parse(p.token)!!))
        val fresh = StyleParams(StylePreset.FADED, tone = 0.11f)
        val b = StyleEngine.lutFor(fresh)
        // Push them out of the cache and regenerate: same numbers.
        StylePreset.entries.forEach { StyleEngine.lutFor(StyleParams(it, tone = 0.77f)) }
        val again = StyleEngine.lutFor(fresh)
        assertNotSame(b, again)
        assertArrayEquals(b.data, again.data, 0f)
        assertEquals(StyleEngine.LUT_SIZE, a.size)
    }

    @Test
    fun logAndHlgInputsComposeAfterTheViewingTransform() {
        val p = StyleParams.of(StylePreset.VIBRANT)
        val log = StyleEngine.lutFor(p, StyleInput.UC_LOG)
        val viewing = UcLogLuts.toRec709()
        val mid = FloatArray(3)
        val expected = FloatArray(3)
        LutMath.apply(viewing, 0.4f, 0.45f, 0.5f, mid)
        LutMath.apply(StyleEngine.lutFor(p), mid[0], mid[1], mid[2], expected)
        LutMath.apply(log, 0.4f, 0.45f, 0.5f, out)
        assertArrayEquals(expected, out, 0.01f)
        // HLG: black stays black, the HLG reference white lands well above mid grey.
        val hlg = StyleEngine.lutFor(p, StyleInput.HLG)
        LutMath.apply(hlg, 0f, 0f, 0f, out)
        assertTrue(out.all { it < 0.03f })
        LutMath.apply(hlg, 0.75f, 0.75f, 0.75f, out)
        assertTrue(out[1] > 0.6f)
    }

    @Test
    fun applyStyleOnPixelsKeepsAlphaAndMatchesTheLut() {
        val pixels = IntArray(64) { i -> (0x80 shl 24) or ((i * 4) shl 16) or ((255 - i * 3) shl 8) or (i * 2) }
        val copy = pixels.copyOf()
        val p = StyleParams.of(StylePreset.WARM)
        StyleEngine.applyStyle(pixels, p, offset = 8, length = 48)
        assertEquals(copy[0], pixels[0])
        assertEquals(copy[63], pixels[63])
        val expected = LutMath.applyToArgb(StyleEngine.lutFor(p), copy)
        for (i in 8 until 56) assertEquals(expected[i], pixels[i])
        // Identity: untouched.
        val same = copy.copyOf()
        StyleEngine.applyStyle(same, StyleParams.of(StylePreset.STANDARD))
        assertArrayEquals(copy, same)
    }

    private fun gain(p: StyleParams, c: FloatArray): Float {
        StyleEngine.transform(p, c[0], c[1], c[2], out)
        val inY = luma(c)
        val outY = luma(out)
        val inChroma = c.maxOf { abs(it - inY) }
        val outChroma = out.maxOf { abs(it - outY) }
        return outChroma / inChroma
    }

    private fun hue(c: FloatArray): Float {
        val y = luma(c)
        return Math.toDegrees(atan2((c[0] - y).toDouble(), (c[2] - y).toDouble())).toFloat()
    }
}
