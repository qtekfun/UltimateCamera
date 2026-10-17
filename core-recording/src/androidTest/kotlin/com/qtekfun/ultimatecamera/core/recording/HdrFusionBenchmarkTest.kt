package com.qtekfun.ultimatecamera.core.recording

import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.exp
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the D64 follow-up: the first CPU fusion took over two minutes for a 12 MP bracket on the Pixel 8.
 * Fuses three synthetic 4000 x 3000 I420 frames (−2/0/+2 EV of a backlit window) exactly as a photo does — align,
 * fuse, gain map on Android 14+, JPEG 95 — and asserts the time budget. Logs `FUSION_MS=<n>` (tag UcHdrBench).
 */
@RunWith(AndroidJUnit4::class)
class HdrFusionBenchmarkTest {
    private lateinit var dir: File
    private val width = 4000
    private val height = 3000

    @Before
    fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "hdr-bench").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** One synthetic I420 frame of the scene at [gain], written in rows (never whole in memory). */
    private fun frame(index: Int, gain: Float): File {
        val file = File(dir, "frame$index${HdrWorkDir.FRAME_SUFFIX}")
        file.outputStream().buffered(1 shl 18).use { out ->
            val row = ByteArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val window = 1f / (1f + exp(-(x - width * 0.6f) / 40f))
                    val texture = 0.03f * sin(x * 0.05f) * sin(y * 0.07f)
                    row[x] = ((0.04f + 0.9f * window + texture) * gain * 255f).toInt().coerceIn(0, 255).toByte()
                }
                out.write(row)
            }
            val chroma = ByteArray(width / 2) { 128.toByte() }
            repeat(height) { out.write(chroma) } // U then V: (h/2 rows) x 2 planes = h rows of w/2.
        }
        return file
    }

    @Test
    fun twelveMegapixelFusionStaysWithinBudget() {
        val frames = listOf(frame(0, 0.25f), frame(1, 1f), frame(2, 4f))
        val output = File(dir, "fused.jpg")
        val pipeline = HdrFusionPipeline(File(dir, "work"))
        val gainMap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val start = SystemClock.elapsedRealtime()
        pipeline.fuse(frames, listOf(0.25, 1.0, 4.0), reference = 1, width, height, rotation = 90, gainMap = gainMap, quality = 95, output = output)
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "FUSION_MS=$ms (${Runtime.getRuntime().availableProcessors()} cores, gain map $gainMap)")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertEquals("turned upright", height, bounds.outWidth)
        assertEquals(width, bounds.outHeight)
        assertTrue("fusion took $ms ms, budget $BUDGET_MS ms", ms < BUDGET_MS)
    }

    private companion object {
        const val TAG = "UcHdrBench"
        const val BUDGET_MS = 3_000L
    }
}
