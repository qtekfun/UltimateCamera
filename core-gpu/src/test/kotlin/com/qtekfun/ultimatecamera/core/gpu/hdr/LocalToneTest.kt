package com.qtekfun.ultimatecamera.core.gpu.hdr

import com.qtekfun.ultimatecamera.core.gpu.hdr.SyntheticScenes.HEIGHT
import com.qtekfun.ultimatecamera.core.gpu.hdr.SyntheticScenes.Indoor
import com.qtekfun.ultimatecamera.core.gpu.hdr.SyntheticScenes.WIDTH
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D84, the owner's v0.7.1 comparison (OPPO, indoor room: white wall, dark corridor through a door): "seguimos sin
 * conseguir el estilo". Our Ultra HDR photos kept the camera's JPEG_R SDR base, whose white wall sits at about 210
 * (p99 210–218 vs 243 on the phone's own camera), and the old clarity (a Gaussian unsharp mask) and S curve pushed the
 * corridor down. The scenes are generated ([SyntheticScenes.indoorJpegR]); no photo is kept in the repository.
 */
class LocalToneTest {
    private fun render(rgb: IntArray, params: RenderParams = RenderParams.BASE): IntArray {
        val out = IntArray(rgb.size)
        val source = RowSource { y, r, g, b ->
            for (x in 0 until WIDTH) {
                val p = rgb[y * WIDTH + x]
                r[x] = ((p shr 16) and 0xFF) / 255f
                g[x] = ((p shr 8) and 0xFF) / 255f
                b[x] = (p and 0xFF) / 255f
            }
        }
        PhotoLook(WIDTH, HEIGHT, params).apply(source, { y0, rows, r, g, b, _ ->
            for (i in 0 until rows * WIDTH) out[y0 * WIDTH + i] = (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
        })
        return out
    }

    private fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun luma(p: Int) = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)

    /** Mean of each channel over the pixels where [inside] holds. */
    private fun mean(rgb: IntArray, inside: (Int, Int) -> Boolean): DoubleArray {
        val sum = DoubleArray(3)
        var n = 0
        for (i in rgb.indices) {
            if (!inside(i % WIDTH, i / WIDTH)) continue
            sum[0] += ((rgb[i] shr 16) and 0xFF).toDouble()
            sum[1] += ((rgb[i] shr 8) and 0xFF).toDouble()
            sum[2] += (rgb[i] and 0xFF).toDouble()
            n++
        }
        return DoubleArray(3) { sum[it] / n }
    }

    private fun lumaOf(c: DoubleArray) = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]

    private fun stats(rgb: IntArray) = LumaStats.Builder().apply { rgb.forEach(::add) }.build()

    @Test
    fun theSceneIsTheCameraBaseTheOwnerReported() {
        val s = stats(SyntheticScenes.indoorJpegR())
        val wall = mean(SyntheticScenes.indoorJpegR(), Indoor::wall)
        assertTrue("scene $s", s.p99 in 200..216 && s.p1 in 18..28)
        assertEquals(206.0, lumaOf(wall), 4.0)
    }

    @Test
    fun theWhiteWallLandsWhereThePhonesOwnCameraPutsIt() {
        val out = render(SyntheticScenes.indoorJpegR())
        val wall = mean(out, Indoor::wall)
        val s = stats(out)
        println("LOCAL_TONE indoor: $s wall ${wall.toList()}")
        // The phone's own camera: wall ≈ [230, 238, 243], p99 243.
        for (c in wall) assertEquals("wall ${wall.toList()}", 240.0, c, 8.0)
        assertTrue("p99: $s", s.p99 in 237..249)
        assertTrue("clipped: $s", s.clipped < 0.01)
    }

    @Test
    fun theDarkCorridorIsOpenedNotCrushed() {
        val scene = SyntheticScenes.indoorJpegR()
        val before = lumaOf(mean(scene, Indoor::corridor))
        val after = lumaOf(mean(render(scene), Indoor::corridor))
        println("LOCAL_TONE corridor: $before -> $after")
        assertTrue("corridor $before -> $after", after >= before * 0.98)
    }

    @Test
    fun blacksGoToTheFloorWithoutBeingLifted() {
        val out = render(SyntheticScenes.indoorJpegR())
        val black = lumaOf(mean(out, Indoor::black))
        val s = stats(out)
        assertTrue("p1: $s", s.p1 in 4..15)
        assertTrue("black object: $black", black < 16.0)
    }

    @Test
    fun noHaloAtAHardEdge() {
        val out = render(SyntheticScenes.edge())
        val row = HEIGHT / 2
        fun at(x: Int) = luma(out[row * WIDTH + x])
        val brightFar = at(10)
        val darkFar = at(WIDTH - 10)
        var overshoot = 0.0
        var undershoot = 0.0
        for (x in 0 until WIDTH / 2) overshoot = maxOf(overshoot, at(x) - brightFar)
        for (x in WIDTH / 2 until WIDTH) undershoot = maxOf(undershoot, darkFar - at(x))
        println("LOCAL_TONE edge: bright $brightFar dark $darkFar overshoot $overshoot undershoot $undershoot")
        assertTrue("overshoot $overshoot", overshoot <= 3.0)
        assertTrue("undershoot $undershoot", undershoot <= 3.0)
    }

    @Test
    fun aGreyRampStaysMonotonic() {
        val out = render(SyntheticScenes.ramp())
        val row = HEIGHT / 2
        fun at(x: Int) = luma(out[row * WIDTH + x])
        // Pixel to pixel, only the 8-bit steps of the input can show (the base keeps moving where the input repeats a
        // level): at most 2 levels back. Over 8 pixels it never goes down.
        for (x in 1 until WIDTH) assertTrue("x $x", at(x) >= at(x - 1) - 2.0)
        for (x in 8 until WIDTH step 8) assertTrue("x $x", at(x) > at(x - 8))
        assertTrue(at(WIDTH - 1) >= 250.0)
        assertTrue(at(0) <= 2.0)
    }

    @Test
    fun skinKeepsItsHueAndSkyIsNotPushed() {
        val scene = SyntheticScenes.indoorJpegR()
        val out = render(scene)
        fun hue(c: DoubleArray) = Math.toDegrees(atan2(Math.sqrt(3.0) * (c[1] - c[2]), 2 * c[0] - c[1] - c[2]))
        fun sat(c: DoubleArray) = (c.max() - c.min()) / c.max()
        val skinIn = mean(scene, Indoor::skin)
        val skinOut = mean(out, Indoor::skin)
        assertTrue("skin hue ${hue(skinIn)} -> ${hue(skinOut)}", abs(hue(skinIn) - hue(skinOut)) < 6.0)
        assertTrue("skin saturation ${sat(skinIn)} -> ${sat(skinOut)}", sat(skinOut) < sat(skinIn) * 1.35)
        val skyIn = mean(scene, Indoor::sky)
        val skyOut = mean(out, Indoor::sky)
        assertTrue("sky saturation ${sat(skyIn)} -> ${sat(skyOut)}", sat(skyOut) < sat(skyIn) * 1.5)
    }

    @Test
    fun saturationLandsInTheRangeOfThePhonesOwnCamera() {
        val before = stats(SyntheticScenes.indoorJpegR())
        val after = stats(render(SyntheticScenes.indoorJpegR()))
        println("LOCAL_TONE saturation ${before.saturation} -> ${after.saturation}")
        assertTrue("saturation $before -> $after", after.saturation > before.saturation * 1.2 && after.saturation < before.saturation * 1.6)
    }

    @Test
    fun aBrightCameraJpegGetsNoWhiteGain() {
        val look = PhotoLook(WIDTH, HEIGHT)
        val rgb = SyntheticScenes.ramp()
        look.apply({ y, r, g, b ->
            for (x in 0 until WIDTH) {
                val v = (rgb[y * WIDTH + x] and 0xFF) / 255f
                r[x] = v
                g[x] = v
                b[x] = v
            }
        }, { _, _, _, _, _, _ -> })
        assertEquals(0f, look.whiteGain, 1e-3f)
        assertEquals(0f, look.blackPoint, 1e-3f)
    }

    @Test
    fun theToneMapIsMonotonicAndKeepsTheEnds() {
        val tone = ToneMap(RenderParams.BASE, 0.09f, 0.8f)
        assertTrue("white gain ${tone.whiteGain}", tone.whiteGain in 0.2f..1f)
        for (base in listOf(-9f, -6f, -4f, -2f, 0f)) {
            var previous = -1f
            for (i in 0..1200) {
                val l = LogLuma.LOG_FLOOR + i * 0.01f
                val v = tone.map(l, base)
                assertTrue("base $base at $l", v >= previous - 1e-6f)
                previous = v
            }
        }
        assertEquals(1f, tone.finish(tone.map(0f + 0f, 0f)), 1e-3f)
        assertEquals(0f, tone.finish(tone.map(LogLuma.LOG_FLOOR, LogLuma.LOG_FLOOR)), 1e-3f)
    }

    @Test
    fun shadowsAreLiftedOnlyBelowThePivotAndNeverAtTrueBlack() {
        val p = RenderParams.BASE
        val tone = ToneMap(p, 0f, 1f)
        assertEquals(0f, tone.shadowLift(p.shadowPivot + 0.5f), 0f)
        assertEquals(p.shadowStrength * 2f, tone.shadowLift(p.shadowPivot - 2f), 1e-5f)
        assertTrue(tone.shadowLift(-7f) <= p.maxShadowLift)
        assertEquals(0f, tone.shadowLift(p.shadowFloor - 0.1f), 0f)
    }

    @Test
    fun theGuidedBaseFollowsEdgesAndSmoothsTexture() {
        val w = 64
        val step = Plane(w, 8, FloatArray(w * 8) { if (it % w < w / 2) -1f else -6f })
        val base = GuidedBase.of(step, 4, 0.25f)
        for (x in 0 until w) {
            val l = step.data[x]
            assertEquals("x $x", l, base.a.data[x] * l + base.b.data[x], 0.15f)
        }
        val texture = Plane(w, 8, FloatArray(w * 8) { if (it % 2 == 0) -3.1f else -2.9f })
        val smooth = GuidedBase.of(texture, 4, 0.25f)
        val l = texture.data[10]
        assertEquals(-3f, smooth.a.data[10] * l + smooth.b.data[10], 0.02f)
    }
}
