package com.qtekfun.ultimatecamera.core.gpu.hdr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GPU fusion must give the CPU [FastExposureFusion] result (same maths; the GPU keeps the small images in 8 bits
 * and writes 8-bit output, so a few levels of difference are allowed), upright for every turn.
 */
@RunWith(AndroidJUnit4::class)
class GpuExposureFusionTest {
    private val w = 320
    private val h = 240

    /** A backlit, textured scene at [gain] as I420 bytes (grey chroma with a warm tint on the right). */
    private fun frame(gain: Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(w * h * 3 / 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val window = 1f / (1f + exp(-(x - w * 0.6f) / 8f))
                val v = (0.05f + 0.9f * window + 0.05f * sin(x * 0.4f) * sin(y * 0.3f)) * gain
                buffer.put((v * 255f).toInt().coerceIn(0, 255).toByte())
            }
        }
        repeat(w / 2 * h / 2) { i -> buffer.put((if (i % (w / 2) > w / 4) 120 else 128).toByte()) }
        repeat(w / 2 * h / 2) { i -> buffer.put((if (i % (w / 2) > w / 4) 140 else 128).toByte()) }
        buffer.position(0)
        return buffer
    }

    private fun rows(f: ByteBuffer) = RowSource { y, r, g, b ->
        for (x in 0 until w) {
            val yy = (f.get(y * w + x).toInt() and 0xFF).toFloat()
            val c = (y / 2) * (w / 2) + x / 2
            val u = (f.get(w * h + c).toInt() and 0xFF) - 128f
            val v = (f.get(w * h + w * h / 4 + c).toInt() and 0xFF) - 128f
            r[x] = ((yy + 1.402f * v) / 255f).coerceIn(0f, 1f)
            g[x] = ((yy - 0.344136f * u - 0.714136f * v) / 255f).coerceIn(0f, 1f)
            b[x] = ((yy + 1.772f * u) / 255f).coerceIn(0f, 1f)
        }
    }

    private val frames = listOf(frame(0.25f), frame(1f), frame(4f))

    /** CPU reference as ARGB ints, sensor orientation. */
    private fun cpu(): IntArray {
        val out = IntArray(w * h)
        FastExposureFusion(w, h).fuse(frames.map(::rows), null, { y0, rows, r, g, b, _ ->
            for (i in 0 until rows * w) out[y0 * w + i] = (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
        })
        return out
    }

    private fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun gpu(rotation: Int): Pair<Int, IntArray> {
        val gpu = GpuExposureFusion(w, h)
        val pool = Executors.newFixedThreadPool(4)
        try {
            frames.forEachIndexed { k, f -> gpu.upload(k, f) }
            val low = frames.indices.map(gpu::downscale)
            val stage = LowStage.of(low, listOf(0 to 0, 0 to 0, 0 to 0), pool)
            gpu.setLow(stage.fused, stage.aligned, stage.weights)
            val outW = if (rotation % 180 == 0) w else h
            val out = IntArray(w * h)
            gpu.render(listOf(0 to 0, 0 to 0, 0 to 0), rotation) { y0, rows, argb -> System.arraycopy(argb, 0, out, y0 * outW, rows * outW) }
            return outW to out
        } finally {
            pool.shutdown()
            gpu.release()
        }
    }

    /** CPU pixel (x, y) where the upright GPU image of [rotation] has it. */
    private fun turned(x: Int, y: Int, rotation: Int, outW: Int): Int = when (rotation) {
        90 -> x * outW + (h - 1 - y)
        180 -> (h - 1 - y) * outW + (w - 1 - x)
        270 -> (w - 1 - x) * outW + y
        else -> y * outW + x
    }

    /** Mean and worst per-channel difference between the CPU image and the GPU image of [rotation]. */
    private fun difference(reference: IntArray, rotation: Int): Pair<Double, Int> {
        val (outW, out) = gpu(rotation)
        var sum = 0L
        var worst = 0
        for (i in 0 until w * h) {
            val a = reference[i]
            val b = out[turned(i % w, i / w, rotation, outW)]
            for (shift in intArrayOf(0, 8, 16)) {
                val d = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                sum += d
                worst = maxOf(worst, d)
            }
        }
        return sum.toDouble() / (3.0 * w * h) to worst
    }

    @Test
    fun matchesTheCpuFusionInEveryTurn() {
        val reference = cpu()
        for (rotation in listOf(0, 90, 180, 270)) {
            val (mean, worst) = difference(reference, rotation)
            Log.i(TAG, "turn $rotation: mean $mean worst $worst")
            assertTrue("turn $rotation: mean $mean worst $worst", mean < 2.0 && worst <= 16)
        }
    }

    @Test
    fun gainMapIsAboveOneOnlyWhereTheBracketSeesMoreLight() {
        val gpu = GpuExposureFusion(w, h)
        val pool = Executors.newFixedThreadPool(4)
        try {
            frames.forEachIndexed { k, f -> gpu.upload(k, f) }
            val stage = LowStage.of(frames.indices.map(gpu::downscale), listOf(0 to 0, 0 to 0, 0 to 0), pool)
            gpu.setLow(stage.fused, stage.aligned, stage.weights)
            val gm = gpu.gainMap(listOf(0 to 0, 0 to 0, 0 to 0), 0, doubleArrayOf(0.25, 1.0, 4.0), w / 4, h / 4)
            val values = gm.map { (it ushr 24) and 0xFF }
            assertTrue("some gain in the window", values.any { it > 0 })
            assertTrue("all within 0..255", values.all { it in 0..255 })
        } finally {
            pool.shutdown()
            gpu.release()
        }
    }

    private companion object {
        const val TAG = "GpuExposureFusionTest"
    }
}
