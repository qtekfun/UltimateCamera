package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.camera.LensOptics
import com.qtekfun.ultimatecamera.core.camera.StereoPair
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import java.util.Random
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePortraitPreviewTest {
    /** Runs tasks only when told to, so a test can hold the worker "busy". */
    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayList<Runnable>()
        var shut = false

        override fun execute(command: Runnable) {
            if (shut) throw RejectedExecutionException("closed")
            tasks += command
        }

        fun runAll() {
            val now = tasks.toList()
            tasks.clear()
            now.forEach(Runnable::run)
        }

        override fun shutdown() {
            shut = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shut = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown() = shut

        override fun isTerminated() = shut

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /** DEPTH16 of a near subject (500 mm) on the left quarter, a far wall (4000 mm) elsewhere. */
    private fun depth16(w: Int = 40, h: Int = 30) = DepthFrame.Depth16(ShortArray(w * h) { i -> if (i % w < w / 4) 500 else 4000 }, w, h, rowStride = w)

    private val optics = LensOptics(6.9f, 9.8f, 7.4f)
    private var clock = 0L
    private val delivered = ArrayList<LiveDepth>()
    private val skipped = ArrayList<String>()
    private var look = PortraitLook(fNumber = 2f)
    private val executor = ManualExecutor()
    private val preview = LivePortraitPreview(100_000_000, { look }, { delivered += it }, { clock }, { skipped += it }, executor)

    @Test
    fun aDepthFrameBecomesInverseDepthFocusedOnTheTappedPoint() {
        look = look.copy(focusX = 0.1f, focusY = 0.5f)
        preview.onDepth(depth16())
        executor.runAll()
        val update = delivered.single()
        assertEquals(2f, update.inverse[0, 0], 1e-6f)
        assertEquals(0.25f, update.inverse[39, 0], 1e-6f)
        assertEquals("the subject is in focus", 2f, update.focus, 1e-6f)
        assertEquals(2f, update.fNumber, 0f)
    }

    @Test
    fun theFocusFollowsTheLatestTapWithoutReopening() {
        preview.onDepth(depth16())
        executor.runAll()
        assertEquals("centre: the wall", 0.25f, delivered.last().focus, 1e-6f)
        look = look.copy(focusX = 0.2f)
        clock += 200_000_000
        preview.onDepth(depth16())
        executor.runAll()
        assertEquals(2f, delivered.last().focus, 1e-6f)
    }

    @Test
    fun framesAreDroppedWhileTheWorkerIsBusyAndWhenTooSoon() {
        preview.onDepth(depth16())
        clock = 50_000_000
        preview.onDepth(depth16()) // The first one is still being worked on.
        assertEquals(1, executor.tasks.size)
        executor.runAll()
        clock = 60_000_000
        preview.onDepth(depth16()) // Free again, but only 60 ms after the last one taken.
        assertEquals(0, executor.tasks.size)
        clock = 100_000_000
        preview.onDepth(depth16())
        executor.runAll()
        assertEquals(2, delivered.size)
        assertEquals(2, preview.dropped)
    }

    @Test
    fun aFrameThatFailsIsSkippedAndTheNextOneStillGoes() {
        // A stereo frame whose buffers are shorter than it says: the conversion throws.
        val broken = DepthFrame.Stereo(ByteArray(4), ByteArray(4), 64, 48, StereoPair("2", "3", optics, optics))
        preview.onDepth(broken)
        executor.runAll()
        assertEquals(1, skipped.size)
        clock += 200_000_000
        preview.onDepth(depth16())
        executor.runAll()
        assertEquals(1, delivered.size)
    }

    @Test
    fun afterCloseFramesAreRefusedWithoutBlockingTheGate() {
        preview.close()
        preview.onDepth(depth16())
        assertTrue(delivered.isEmpty())
        assertEquals(0, executor.tasks.size)
    }

    @Test
    fun liveStereoIsMatchedAtAQuarterOfTheStreamSize() {
        val w = 160
        val h = 120
        val random = Random(5)
        val texture = ByteArray((w + 40) * h) { random.nextInt(256).toByte() }
        val main = ByteArray(w * h) { i -> texture[(i / w) * (w + 40) + i % w + 20] }
        val second = ByteArray(w * h) { i -> texture[(i / w) * (w + 40) + i % w + 20 - 4] }
        val frame = DepthFrame.Stereo(main, second, w, h, StereoPair("2", "3", optics, optics))
        val live = PortraitMaps.liveDepth(frame, PortraitLook(2f))
        assertEquals(w / 4, live.inverse.width)
        assertEquals(h / 4, live.inverse.height)
        assertTrue("one depth everywhere: in focus", live.focus > 0f)
    }
}
