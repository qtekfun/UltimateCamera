package com.qtekfun.ultimatecamera

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.settings.ControlOption
import com.qtekfun.ultimatecamera.core.settings.DataStoreSettingsRepository
import com.qtekfun.ultimatecamera.feature.camera.background.RecordingService
import com.qtekfun.ultimatecamera.feature.camera.background.RecordingServiceBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D66 on a real phone (the lead runs it on the Pixel 8): REC, Home, 10 s off screen, Stop through the service (the
 * notification's action), then the saved MP4 must hold frames for the whole span, not only the visible part.
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.qtekfun.ultimatecamera.BackgroundRecordingTest`
 */
@RunWith(AndroidJUnit4::class)
class BackgroundRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aTakeKeepsRecordingOffScreenAndStopsFromTheService() {
        val automation = instrumentation.uiAutomation
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) {
            automation.grantRuntimePermission(context.packageName, permission)
        }
        val settings = DataStoreSettingsRepository.getInstance(context)
        runBlocking {
            settings.setAutoConfigOffered(true)
            settings.setControlOption(ControlOption.VOLUME_KEYS_RECORD, true)
            settings.setControlOption(ControlOption.BACKGROUND_RECORDING, true)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(OPEN_MS)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            assertTrue("REC did not start", waitFor { RecordingServiceBridge.status.value.recording })
            val started = SystemClock.elapsedRealtime()
            val status = RecordingServiceBridge.status.value
            val fileName = status.fileName
            assertNotNull(fileName)

            // Off screen: Home where the shell allows it, else the activity is stopped directly.
            if (!automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(BACKGROUND_MS)
            assertTrue("the take stopped off screen", RecordingServiceBridge.status.value.recording)

            context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            assertTrue("Stop did not end the take", waitFor { !RecordingServiceBridge.status.value.recording })
            val spanMs = SystemClock.elapsedRealtime() - started

            val uri = waitForVideo(fileName!!)
            assertNotNull("$fileName not in MediaStore", uri)
            val (frames, lastUs) = videoSamples(uri!!)
            val expected = status.fps * spanMs / 1000.0
            assertTrue("only $frames frames for ${spanMs}ms at ${status.fps} fps", frames >= expected * MIN_FRAME_SHARE)
            assertTrue("video ends at ${lastUs / 1000}ms of ${spanMs}ms", lastUs / 1000 >= spanMs - MAX_TAIL_GAP_MS)
        }
    }

    private fun waitFor(timeoutMs: Long = WAIT_MS, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }

    private fun waitForVideo(fileName: String): Uri? {
        var found: Uri? = null
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        waitFor {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
                arrayOf(fileName),
                null
            )?.use { if (it.moveToFirst()) found = ContentUris.withAppendedId(collection, it.getLong(0)) }
            found != null
        }
        return found
    }

    /** Video sample count and the last sample's presentation time in µs. */
    private fun videoSamples(uri: Uri): Pair<Int, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            var frames = 0
            var last = 0L
            while (extractor.sampleTime >= 0) {
                frames++
                last = maxOf(last, extractor.sampleTime)
                extractor.advance()
            }
            return frames to last
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val OPEN_MS = 4_000L
        const val BACKGROUND_MS = 10_000L
        const val WAIT_MS = 8_000L
        const val POLL_MS = 100L

        /** Start/stop latency and a few dropped frames are tolerated; a take saved at ON_STOP would hold far fewer. */
        const val MIN_FRAME_SHARE = 0.85
        const val MAX_TAIL_GAP_MS = 1_500L
    }
}
