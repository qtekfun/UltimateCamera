package com.qtekfun.ultimatecamera.core.recording

import android.Manifest
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.codec.VideoContainer
import com.qtekfun.ultimatecamera.core.codec.mp4.ChannelSeekableFile
import com.qtekfun.ultimatecamera.core.codec.mp4.Mp4Inspector
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real 3 s take through [CameraController] with the post-process on (D69): the file must come out with a `tmcd`
 * track referenced by the video track and the time-of-day start. The files stay in the test app's external files
 * folder (`adb pull /sdcard/Android/data/com.qtekfun.ultimatecamera.core.recording.test/files/`) for `ffprobe`, which
 * should show `timecode` on the video and data streams (and `major_brand=qt  ` for the MOV).
 */
@RunWith(AndroidJUnit4::class)
class TimecodeTakeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aThreeSecondTakeGetsATimecodeTrackInMp4AndMov() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val controller = CameraController(context)
        try {
            val cameraId = controller.mainBackCamera()
            assumeNotNull(cameraId)
            controller.open(cameraId!!, RecordingProfile(1920, 1080, 30, VideoCodec.HEVC, tenBit = false))
            assertTrue(controller.state.value.toString(), controller.state.value is CameraState.Previewing)
            for (container in VideoContainer.entries) {
                val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "uc-timecode-take.${container.extension}")
                val output = FileOutput(file, container)
                controller.startRecording(output, audioConfig = null, orientationHint = 0)
                assertTrue(controller.state.value is CameraState.Recording)
                delay(TAKE_MS)
                assertNull(controller.stopRecording())
                check(file, container)
            }
        } finally {
            controller.close()
            controller.shutdown()
        }
    }

    private fun check(file: File, container: VideoContainer) {
        val summary = RandomAccessFile(file, "r").use { Mp4Inspector.inspect(ChannelSeekableFile(it.channel)) }
        Log.i("TimecodeTakeTest", "${file.absolutePath}: $summary ${summary.timecode?.label}")
        if (container == VideoContainer.MOV) assertEquals("qt  ", summary.majorBrand)
        val video = summary.tracks.first { it.handler == "vide" }
        val tmcd = summary.tracks.single { it.handler == "tmcd" }
        assertEquals(tmcd.trackId, video.timecodeRef)
        val tc = summary.timecode
        assertNotNull(tc)
        assertEquals(30, tc!!.numerator)
        assertNotNull(summary.creationDay)
        if (container == VideoContainer.MOV) assertTrue(summary.tracks.all { it.component == "mhlr" })
    }

    /** A take into a plain file opened read-write, as the SAF/MediaStore descriptors are. */
    private class FileOutput(file: File, override val container: VideoContainer) : VideoOutput {
        private val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        override val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor
        override val displayName: String = file.name

        override fun finish(keep: Boolean) = pfd.close()
    }

    private companion object {
        const val TAKE_MS = 3_000L
    }
}
