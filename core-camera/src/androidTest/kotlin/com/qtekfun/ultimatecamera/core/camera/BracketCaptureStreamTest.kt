package com.qtekfun.ultimatecamera.core.camera

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real camera: a −2/0/+2 EV bracket whose consumer is as slow as a disk write delivers all three frames. Regression
 * for the Pixel 8 crash ("maxImages (2) has already been acquired") that killed the photo thread on every HDR shot.
 */
@RunWith(AndroidJUnit4::class)
class BracketCaptureStreamTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val thread = HandlerThread("bracket-test").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        texture = SurfaceTexture(0).apply { detachFromGLContext() }
        surface = Surface(texture)
    }

    @After
    fun tearDown() {
        surface.release()
        texture.release()
        thread.quitSafely()
    }

    @Test
    fun aThreeFrameBracketWithASlowConsumerDeliversEveryFrame() = runBlocking {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        assumeNotNull(id)
        val sizes = BracketCaptureStream.sizes(manager.getCameraCharacteristics(id!!))
        assumeNotNull(sizes)
        texture.setDefaultBufferSize(sizes!!.preview.width, sizes.preview.height)
        val stream = BracketCaptureStream.open(manager, id, surface, sizes.jpeg, handler) { }
        try {
            // Let the preview meter first, as in the app: only then does the bracket use the manual three-frame burst
            // that overlapped frames (right after opening it falls back to one-at-a-time captures and never crashed).
            delay(METER_MS)
            val delivered = mutableListOf<Int>()
            val exposures = withTimeout(TIMEOUT_MS) {
                stream.bracket(listOf(-2.0, 0.0, 2.0)) { index, image, _ ->
                    // Hold the frame as long as writing ~12 MP of YUV to flash takes on a modest phone.
                    Thread.sleep(SLOW_WRITE_MS)
                    check(image.width > 0)
                    delivered += index
                }
            }
            assertEquals(listOf(0, 1, 2), delivered)
            assertTrue("manual burst expected, got exposures $exposures", exposures.first() < 1.0 && exposures.last() > 1.0)
        } finally {
            stream.close()
        }
    }

    private companion object {
        const val SLOW_WRITE_MS = 400L
        const val METER_MS = 2_000L
        const val TIMEOUT_MS = 30_000L
    }
}
