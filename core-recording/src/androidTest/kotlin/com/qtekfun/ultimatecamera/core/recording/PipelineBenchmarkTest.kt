package com.qtekfun.ultimatecamera.core.recording

import android.Manifest
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 benchmark (REQ-NFR-001, D14, D68): the real pipeline camera → [com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer]
 * → HEVC encoder at 4K30 10-bit HLG, no preview, recorded for [WINDOW_S] s after a [WARM_UP_S] s warm-up. Asserts zero
 * dropped frames, the render p95 within [RENDER_P95_BUDGET_MS] and at most [MAX_GC] garbage collections in the window
 * (`art.gc.gc-count`). Allocations of the whole process are counted with `Debug.startAllocCounting` and logged (camera
 * framework and binder threads allocate too, so only GC is asserted). Logs `PERF_*` lines under [TAG]:
 *
 *     adb shell am instrument -w -e class com.qtekfun.ultimatecamera.core.recording.PipelineBenchmarkTest \
 *       com.qtekfun.ultimatecamera.core.recording.test/androidx.test.runner.AndroidJUnitRunner
 *     adb logcat -s UCPERF
 *
 * Skipped (assumption) on a phone that cannot open 4K30 10-bit on the main back camera.
 */
@RunWith(AndroidJUnit4::class)
class PipelineBenchmarkTest {
    @Test
    fun uhd30TenBitHoldsWithoutDropsOrGarbage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val controller = CameraController(context)
        val file = File(context.cacheDir, "perf-benchmark.mp4")
        val output = FileOutput(file)
        try {
            val id = controller.mainBackCamera()
            assumeTrue("no back camera", id != null)
            val profile = RecordingProfile(3840, 2160, FPS, VideoCodec.HEVC, tenBit = true)
            controller.open(id!!, profile)
            val state = controller.state.value
            assumeTrue("4K30 10-bit not available: $state", state is CameraState.Previewing)
            controller.startRecording(output, audioConfig = null, orientationHint = 0)
            delay(WARM_UP_S * MS_PER_S)

            val gcBefore = gcCount()
            val framesBefore = controller.framesEncoded()
            val gapsBefore = controller.encoderGapDrops
            AllocCounter.start()
            val start = SystemClock.elapsedRealtime()
            var worstP95 = 0L
            repeat(WINDOW_S.toInt()) {
                delay(MS_PER_S)
                worstP95 = maxOf(worstP95, controller.renderP95Nanos())
            }
            val elapsedMs = SystemClock.elapsedRealtime() - start
            val (allocs, allocBytes) = AllocCounter.stop()
            val gcs = gcCount() - gcBefore
            val frames = controller.framesEncoded() - framesBefore
            val gaps = controller.encoderGapDrops - gapsBefore
            val expected = (elapsedMs * FPS / MS_PER_S).toInt()
            // Same accounting as the status bar (D47): frames still in flight are not drops.
            val shortfall = (expected - frames - IN_FLIGHT).coerceAtLeast(0)
            val p95Ms = worstP95 / NANOS_PER_MS
            Log.i(TAG, "PERF_PIPELINE config=${profile.label} windowMs=$elapsedMs frames=$frames expected=$expected gaps=$gaps shortfall=$shortfall")
            Log.i(TAG, "PERF_RENDER p95Ms=${"%.2f".format(p95Ms)} budgetMs=$RENDER_P95_BUDGET_MS")
            Log.i(TAG, "PERF_GC count=$gcs max=$MAX_GC")
            Log.i(TAG, "PERF_ALLOC count=$allocs bytes=$allocBytes perFrame=${if (frames > 0) allocs / frames else -1}")
            controller.stopRecording()

            assertEquals("encoder timestamp gaps", 0, gaps)
            assertEquals("frames short of $FPS fps", 0, shortfall)
            assertTrue("render p95 $p95Ms ms over $RENDER_P95_BUDGET_MS ms", p95Ms <= RENDER_P95_BUDGET_MS)
            assertTrue("$gcs GCs in $WINDOW_S s (max $MAX_GC)", gcs <= MAX_GC)
        } finally {
            controller.close()
            controller.shutdown()
            file.delete()
        }
    }

    private fun gcCount(): Long = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0

    /** Process-wide allocation counting: deprecated but still the only per-process counter; the result is only logged. */
    @Suppress("DEPRECATION")
    private object AllocCounter {
        fun start() {
            Debug.resetGlobalAllocCount()
            Debug.resetGlobalAllocSize()
            Debug.startAllocCounting()
        }

        /** Objects and bytes allocated since [start]. */
        fun stop(): Pair<Int, Int> {
            val counts = Debug.getGlobalAllocCount() to Debug.getGlobalAllocSize()
            Debug.stopAllocCounting()
            return counts
        }
    }

    /** A plain seekable file in the app's cache (on disk), as the muxer needs. */
    private class FileOutput(private val file: File) : VideoOutput {
        private val raf = RandomAccessFile(file, "rw")
        override val fileDescriptor: FileDescriptor get() = raf.fd
        override val displayName: String get() = file.name
        override fun finish(keep: Boolean) = raf.close()
        override fun bytesWritten(): Long = file.length()
    }

    private companion object {
        const val TAG = "UCPERF"
        const val FPS = 30
        const val WARM_UP_S = 2L
        const val WINDOW_S = 20L
        const val MS_PER_S = 1000L
        const val IN_FLIGHT = 3
        const val NANOS_PER_MS = 1_000_000.0

        /** A 30 fps frame is 33.3 ms; the GPU stage must leave room for the encoder and the preview. */
        const val RENDER_P95_BUDGET_MS = 16.0

        /**
         * Background GCs still happen (other threads, the test runner); a per-frame allocation on the render path would
         * show as dozens in 20 s.
         */
        const val MAX_GC = 3L
    }
}
