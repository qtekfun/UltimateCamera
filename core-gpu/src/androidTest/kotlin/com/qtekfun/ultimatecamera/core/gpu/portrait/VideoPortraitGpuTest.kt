package com.qtekfun.ultimatecamera.core.gpu.portrait

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Portrait in video on the device's GPU (D89): synthetic camera frames (a fine checkerboard, drawn into the renderer's
 * camera surface) with a synthetic depth map (left half near and in focus, right half far) must come out of the
 * encoder path with the right half blurred and the left half sharp, the blur still on its first rung. No camera, no
 * photo: everything is generated here. Also logs the GL time per frame (`UcPortrait`).
 */
@RunWith(AndroidJUnit4::class)
class VideoPortraitGpuTest {
    private val w = 640
    private val h = 360

    @Test
    fun theBackgroundIsBlurredAndTheSubjectStaysSharpInTheRecording() {
        val renderer = CameraRenderer(EglColorDepth.RGBA8888, w, h, hlg = false)
        val thread = HandlerThread("reader").apply { start() }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val latest = AtomicReference<IntArray?>(null)
        // acquireLatestImage folds frames that arrive together into one callback: wait for any, read the latest.
        val arrived = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val luma = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * plane.rowStride + x * plane.pixelStride
                        val buffer = plane.buffer
                        luma[y * w + x] = ((buffer.get(i).toInt() and 0xFF) + (buffer.get(i + 1).toInt() and 0xFF) + (buffer.get(i + 2).toInt() and 0xFF)) / 3
                    }
                }
                latest.set(luma)
                arrived.countDown()
            }
        }, Handler(thread.looper))
        try {
            renderer.videoPortrait.configure(VideoBlurLevel.HIGH, 30)
            renderer.videoPortrait.setDepth(LiveDepth(depth(), focus = NEAR, fNumber = 1.4f))
            renderer.startEncoding(reader.surface, fromNanos = 0)
            repeat(FRAMES) {
                drawCheckerboard(renderer)
                Thread.sleep(FRAME_GAP_MS)
            }
            assertTrue("no encoded frame", arrived.await(5, TimeUnit.SECONDS))
            val luma = requireNotNull(latest.get())
            val sharp = detail(luma, 0, w / 2 - EDGE)
            val blurred = detail(luma, w / 2 + EDGE, w)
            Log.i("UcPortrait", "video portrait GPU test: detail left $sharp right $blurred, render ${renderer.lastRenderNanos / 1000} us")
            assertTrue("subject lost its detail: $sharp", sharp > MIN_SHARP_DETAIL)
            assertTrue("background not blurred: left $sharp right $blurred", blurred < sharp * MAX_BLURRED_RATIO)
            assertNull(renderer.videoPortrait.problem)
            assertEquals(VideoBlurLevel.HIGH, renderer.videoPortrait.level)
        } finally {
            renderer.stopEncoding()
            renderer.release()
            reader.close()
            thread.quitSafely()
        }
    }

    /** Inverse depth, 32 x 18: the left half at [NEAR] dioptres (the focus), the right half at [FAR]. */
    private fun depth(): Plane = Plane(32, 18, FloatArray(32 * 18) { i -> if (i % 32 < 16) NEAR else FAR })

    /** A 4-pixel black and white checkerboard over the whole frame. */
    private fun drawCheckerboard(renderer: CameraRenderer) {
        val canvas = renderer.cameraSurface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            val white = Paint().apply { color = Color.WHITE }
            val columns = w / CELL
            for (cell in 0 until columns * (h / CELL)) {
                val cx = cell % columns
                val cy = cell / columns
                if ((cx + cy) % 2 ==
                    0
                ) {
                    canvas.drawRect((cx * CELL).toFloat(), (cy * CELL).toFloat(), ((cx + 1) * CELL).toFloat(), ((cy + 1) * CELL).toFloat(), white)
                }
            }
        } finally {
            renderer.cameraSurface.unlockCanvasAndPost(canvas)
        }
    }

    /** Mean absolute difference between horizontal neighbours in columns [from] until [to] (the checkerboard's edges). */
    private fun detail(luma: IntArray, from: Int, to: Int): Double {
        var sum = 0L
        var n = 0
        for (y in EDGE until h - EDGE) {
            for (x in from until to - 1) {
                sum += abs(luma[y * w + x + 1] - luma[y * w + x])
                n++
            }
        }
        return sum.toDouble() / n
    }

    private companion object {
        const val FRAMES = 8
        const val FRAME_GAP_MS = 60L
        const val CELL = 4
        const val EDGE = 40
        const val NEAR = 2f
        const val FAR = 0.2f
        const val MIN_SHARP_DETAIL = 20.0
        const val MAX_BLURRED_RATIO = 0.5
    }
}
