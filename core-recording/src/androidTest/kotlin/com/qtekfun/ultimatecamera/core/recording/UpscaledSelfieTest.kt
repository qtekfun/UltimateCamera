package com.qtekfun.ultimatecamera.core.recording

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedCamera
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CameraLadder
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 4K upscaled from a selfie camera's largest real size on a real phone (D112): the camera streams that size, the GPU
 * scales it up, and the file must be 3840x2160 at the full rate. Skipped on a phone whose front camera lists 4K or no
 * 16:9 size between 1080p and 4K.
 */
@RunWith(AndroidJUnit4::class)
class UpscaledSelfieTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(CameraManager::class.java)

    @Test
    fun frontCameraRecordsFourKUpscaledFromItsRealSize(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val front = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        assumeTrue("no front camera", front != null)
        val sensor = CameraLadder.sensorSize(advertised(front!!))
        assumeTrue("the front camera lists 4K or no 16:9 size between 1080p and 4K", sensor != null)
        Log.i(TAG, "camera $front streams ${sensor!!.width}x${sensor.height} for 4K")
        val profile = RecordingProfile(WIDTH, HEIGHT, FPS, VideoCodec.HEVC, tenBit = false, sourceWidth = sensor.width, sourceHeight = sensor.height)
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "upscaled-selfie.mp4").apply { delete() }
        val controller = CameraController(context)
        try {
            controller.open(front, profile)
            assertTrue("camera did not open: ${controller.state.value}", controller.state.value is CameraState.Previewing)
            delay(WARM_UP_MS)
            controller.startRecording(FileOutput(file), audioConfig = null, orientationHint = 0)
            assertTrue("take did not start: ${controller.state.value}", controller.state.value is CameraState.Recording)
            delay(TAKE_MS)
            assertNull(controller.stopRecording())
        } finally {
            controller.close()
            controller.shutdown()
        }
        val (frames, durationUs) = countFrames(file)
        val fps = (frames - 1) * MICROS_PER_SECOND.toDouble() / durationUs
        Log.i(TAG, "upscaled take: $frames frames, %.2f fps, ${file.length()} bytes".format(fps))
        assertTrue("upscaled 4K30 ran at %.2f fps (%d frames in %d us)".format(fps, frames, durationUs), fps >= MIN_FPS)
        file.delete()
    }

    private fun advertised(id: String): AdvertisedCamera {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val modes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { AdvertisedMode(it.width, it.height, FPS) }
        return AdvertisedCamera(id, "FRONT", videoModes = modes)
    }

    /** Video samples and the span of their timestamps; the track must have the recorded (upscaled) size. */
    private fun countFrames(file: File): Pair<Int, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val format = extractor.getTrackFormat(track)
            assertEquals(WIDTH, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(HEIGHT, format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            var frames = 0
            var first = -1L
            var last = -1L
            while (extractor.readSampleData(buffer, 0) >= 0) {
                val t = extractor.sampleTime
                if (first < 0) first = t
                last = maxOf(last, t)
                frames++
                extractor.advance()
            }
            return frames to (last - first)
        } finally {
            extractor.release()
        }
    }

    private class FileOutput(private val file: File) : VideoOutput {
        private val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        override val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor
        override val displayName: String get() = file.name

        override fun finish(keep: Boolean) {
            pfd.close()
            if (!keep) file.delete()
        }

        override fun bytesWritten(): Long = file.length()
    }

    private companion object {
        const val TAG = "UpscaledSelfieTest"
        const val WIDTH = 3840
        const val HEIGHT = 2160
        const val FPS = 30
        const val MIN_FPS = 29.0
        const val WARM_UP_MS = 1_500L
        const val TAKE_MS = 5_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val BUFFER_BYTES = 4 * 1024 * 1024
    }
}
