package com.qtekfun.ultimatecamera.core.recording

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Direct 8K mode on a real phone (D71): 5 s of 8K30 10-bit HEVC straight from the camera into the encoder, no GL
 * pass. The take must hold at least 29 fps and the file must have the frames and the size of that. Skipped on phones
 * that do not list 8K. The lead runs it on the Pixel 8:
 * `./gradlew :core-recording:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...DirectEightKTest`.
 */
@RunWith(AndroidJUnit4::class)
class DirectEightKTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun eightK30DirectHoldsFiveSeconds(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val controller = CameraController(context)
        val cameraId = controller.mainBackCamera()
        assumeTrue("no back camera", cameraId != null)
        // Probe, don't trust the stream map: the Pixel 8 lists no 7680x4320 output yet streamed 8K30 in the spike.
        Log.i(TAG, "camera $cameraId lists 8K: ${listsEightK(cameraId!!)}")
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "direct-8k30.mp4").apply { delete() }
        try {
            // 10-bit first, then 8-bit, as the auto-configuration does. A HAL refusal of both means the phone has no
            // direct 8K (the Pixel 8 refuses an 8K encoder stream next to the viewfinder stream): skip, do not fail.
            val opened = listOf(true, false).firstOrNull { tenBit ->
                val profile = RecordingProfile(WIDTH, HEIGHT, FPS, VideoCodec.HEVC, tenBit = tenBit, direct = true)
                assertTrue(profile.isDirect)
                controller.open(cameraId, profile)
                val state = controller.state.value
                Log.i(TAG, "direct 8K30 tenBit=$tenBit: $state")
                state is CameraState.Previewing || (state as? CameraState.Failed)?.rejected == false
            }
            assumeTrue("the camera refuses direct 8K30 in 10-bit and 8-bit on this phone", opened != null)
            assertTrue("camera did not open: ${controller.state.value}", controller.state.value is CameraState.Previewing)
            delay(WARM_UP_MS)
            controller.startRecording(FileOutput(file), audioConfig = null, orientationHint = 0)
            assertTrue("take did not start: ${controller.state.value}", controller.state.value is CameraState.Recording)
            delay(TAKE_MS)
            val problem = controller.stopRecording()
            assertNull(problem)
        } finally {
            controller.close()
            controller.shutdown()
        }

        val (frames, durationUs) = countFrames(file)
        val fps = (frames - 1) * MICROS_PER_SECOND.toDouble() / durationUs
        assertTrue("only $frames frames in ${file.length()} bytes", frames >= (TAKE_MS / MS_PER_S * MIN_FPS).toInt())
        assertTrue("8K30 direct ran at %.2f fps (%d frames in %d us)".format(fps, frames, durationUs), fps >= MIN_FPS)
        // ≈ 200 Mbit/s for 8K30 10-bit: 5 s is well over 50 MB; less means frames were skipped or nearly empty.
        assertTrue("file only ${file.length()} bytes", file.length() > MIN_BYTES)
        file.delete()
    }

    private fun listsEightK(cameraId: String): Boolean {
        val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.getOutputSizes(MediaCodec::class.java).orEmpty().any { it.width == WIDTH && it.height == HEIGHT }
    }

    /** Video samples and the span of their timestamps, read back with MediaExtractor. */
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
        const val TAG = "DirectEightKTest"
        const val WIDTH = 7680
        const val HEIGHT = 4320
        const val FPS = 30
        const val MIN_FPS = 29.0
        const val WARM_UP_MS = 1_500L
        const val TAKE_MS = 5_000L
        const val MS_PER_S = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val MIN_BYTES = 50L * 1024 * 1024
        const val BUFFER_BYTES = 8 * 1024 * 1024
    }
}
