package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for `GpuExposureFusionTest` failing on the Pixel 8 (D91, mean 14 levels, worst 67): the fusion's
 * weights must not depend on the last bits of the small images, which the GPU computes in another order than the CPU.
 * The GPU path is mirrored here on the JVM ([LowStage] from given small images, then the full-size detail blend of
 * [FastExposureFusion]) and fed small images one float step away from the CPU's.
 */
class FusionPrecisionTest {
    private val w = 320
    private val h = 240
    private val factor = FastExposureFusion.DEFAULT_FACTOR
    private val lw = w / factor
    private val lh = h / factor

    /** The scene of `GpuExposureFusionTest` (backlit window, texture, grey left half, warm right half) as I420. */
    private fun frame(gain: Float): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        var p = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val window = 1f / (1f + exp(-(x - w * 0.6f) / 8f))
                out[p++] = ((0.05f + 0.9f * window + 0.05f * sin(x * 0.4f) * sin(y * 0.3f)) * gain * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }
        repeat(w / 2 * h / 2) { i -> out[p++] = (if (i % (w / 2) > w / 4) 120 else 128).toByte() }
        repeat(w / 2 * h / 2) { i -> out[p++] = (if (i % (w / 2) > w / 4) 140 else 128).toByte() }
        return out
    }

    private val frames = listOf(frame(0.25f), frame(1f), frame(4f))

    private fun rgb(f: ByteArray, x: Int, y: Int, out: FloatArray) {
        val yy = (f[y * w + x].toInt() and 0xFF).toFloat()
        val c = (y / 2) * (w / 2) + x / 2
        val u = (f[w * h + c].toInt() and 0xFF) - 128f
        val v = (f[w * h + w * h / 4 + c].toInt() and 0xFF) - 128f
        out[0] = ((yy + 1.402f * v) / 255f).coerceIn(0f, 1f)
        out[1] = ((yy - 0.344136f * u - 0.714136f * v) / 255f).coerceIn(0f, 1f)
        out[2] = ((yy + 1.772f * u) / 255f).coerceIn(0f, 1f)
    }

    private fun rows(f: ByteArray) = RowSource { y, r, g, b ->
        val c = FloatArray(3)
        for (x in 0 until w) {
            rgb(f, x, y, c)
            r[x] = c[0]
            g[x] = c[1]
            b[x] = c[2]
        }
    }

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private fun cpu(): IntArray {
        val out = IntArray(w * h)
        FastExposureFusion(w, h).fuse(frames.map(::rows), null, { y0, n, r, g, b, _ ->
            for (i in 0 until n * w) out[y0 * w + i] = (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
        }, threads = 2)
        return out
    }

    /** The small images as the CPU makes them, each value moved by [nudge] float steps (what another sum order gives). */
    private fun low(f: ByteArray, nudge: Int): RgbTile {
        val t = RgbTile(lw, lh)
        for (ly in 0 until lh) {
            for (lx in 0 until lw) {
                val s = blockSum(f, lx, ly)
                for (k in 0..2) t.channels[k].data[ly * lw + lx] = nudged(s[k] / (factor * factor), nudge)
            }
        }
        return t
    }

    /** Per channel, the sum over block ([lx], [ly]) in the CPU's order (rows, then columns). */
    private fun blockSum(f: ByteArray, lx: Int, ly: Int): FloatArray {
        val s = FloatArray(3)
        val c = FloatArray(3)
        for (y in ly * factor until (ly + 1) * factor) {
            for (x in lx * factor until (lx + 1) * factor) {
                rgb(f, x, y, c)
                for (k in 0..2) s[k] += c[k]
            }
        }
        return s
    }

    private fun nudged(value: Float, nudge: Int): Float {
        var v = value
        repeat(abs(nudge)) { v = if (nudge > 0) Math.nextUp(v) else Math.nextDown(v) }
        return v
    }

    private fun upsample(p: Plane, x: Int, y: Int): Float {
        val fx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lw - 1).toFloat())
        val fy = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (lh - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, lw - 1)
        val y1 = minOf(y0 + 1, lh - 1)
        val top = p[x0, y0] + (p[x1, y0] - p[x0, y0]) * (fx - x0)
        val bottom = p[x0, y1] + (p[x1, y1] - p[x0, y1]) * (fx - x0)
        return top + (bottom - top) * (fy - y0)
    }

    /** The GPU's arithmetic on the JVM: small-image stage from [nudge]d small images, then the full-size blend. */
    private fun gpuShaped(nudge: Int): IntArray {
        val pool = Executors.newFixedThreadPool(2)
        val stage = try {
            LowStage.of(frames.mapIndexed { k, f -> low(f, if (k == 1) -nudge else nudge) }, List(3) { 0 to 0 }, pool)
        } finally {
            pool.shutdown()
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = blend(stage, x, y)
        }
        return out
    }

    /** The full-size pixel ([x], [y]): fused base plus each frame's weighted detail, as the GPU's blend pass. */
    private fun blend(stage: LowStage, x: Int, y: Int): Int {
        val c = FloatArray(3) { upsample(stage.fused.channels[it], x, y) }
        val px = FloatArray(3)
        for (k in frames.indices) {
            rgb(frames[k], x, y, px)
            val weight = upsample(stage.weights[k], x, y)
            for (ch in 0..2) c[ch] += weight * (px[ch] - upsample(stage.aligned[k].channels[ch], x, y))
        }
        return (q(c[0]) shl 16) or (q(c[1]) shl 8) or q(c[2])
    }

    private fun worst(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) for (shift in intArrayOf(0, 8, 16)) worst = maxOf(worst, abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)))
        return worst
    }

    @Test
    fun greyHasNoSaturationAtAnyLevel() {
        for (level in 0..255) {
            val v = level / 255f
            assertEquals("level $level", 0f, ExposureFusion.saturation(v, v, v))
        }
        // Still the standard deviation of the three channels.
        assertEquals(0.4082483f, ExposureFusion.saturation(1f, 0f, 0.5f), 1e-6f)
    }

    @Test
    fun theGpuShapedPathEqualsTheCpuFusion() {
        assertEquals(0, worst(cpu(), gpuShaped(nudge = 0)))
    }

    @Test
    fun aLastBitChangeInTheSmallImagesChangesTheResultByAtMostTwoLevels() {
        val reference = cpu()
        for (nudge in intArrayOf(1, -1, 2, -2)) {
            val worst = worst(reference, gpuShaped(nudge))
            assertTrue("small images $nudge float steps away: worst $worst levels", worst <= 2)
        }
    }
}
