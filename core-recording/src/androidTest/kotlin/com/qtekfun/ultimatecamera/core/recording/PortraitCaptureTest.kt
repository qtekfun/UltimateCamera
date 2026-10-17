package com.qtekfun.ultimatecamera.core.recording

import android.Manifest
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.PortraitProbe
import com.qtekfun.ultimatecamera.core.camera.PortraitSelector
import com.qtekfun.ultimatecamera.core.storage.AndroidStorageBackend
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D73 on a real phone (the lead runs it on the Pixel 8): picks the first back camera with a portrait depth source
 * (Experimental on, so stereo counts), takes one portrait photo with it, and checks the saved `…_P<nnn>.jpg` decodes to
 * the session's size (and, for our own blur, that `…_P<nnn>_depth.png` exists). Skipped on a phone without a source.
 *
 * `./gradlew :core-recording:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.qtekfun.ultimatecamera.core.recording.PortraitCaptureTest`
 */
@RunWith(AndroidJUnit4::class)
class PortraitCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun takesOnePortraitWithTheAvailableSource(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val pick = manager.cameraIdList.firstNotNullOfOrNull { id ->
            val back = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            val support = PortraitProbe.read(manager, id)
            Log.i(TAG, "camera $id back=$back depth sources ${support.labels()}")
            PortraitSelector.choose(support, experimental = true)?.takeIf { back }?.let { id to it }
        }
        assumeTrue("no portrait depth source on this phone", pick != null)
        val (cameraId, source) = requireNotNull(pick)
        Log.i(TAG, "portrait on camera $cameraId with $source")

        val controller = PhotoController(context)
        val backend = AndroidStorageBackend(context)
        try {
            controller.portraitOptions = PortraitOptions(enabled = true, experimental = true, on = true)
            controller.portraitLook = PortraitLook(fNumber = 2f, saveDepth = true)
            controller.open(cameraId)
            val ready = controller.state.value
            assertTrue("camera not ready: $ready", ready is PhotoState.Ready)
            ready as PhotoState.Ready
            assertEquals("the portrait session fell back", source, ready.portrait)
            Thread.sleep(SETTLE_MS) // 3A converges on the preview.

            // The render time is in logcat (tag UcPortrait: capture and per-stage render times).
            val started = SystemClock.elapsedRealtime()
            val name = withTimeout(TAKE_TIMEOUT_MS) { controller.take(backend, RecordingLocation.Default, cameraId, displayRotationDegrees = 0) }
            Log.i(TAG, "take ${SystemClock.elapsedRealtime() - started} ms")
            assertTrue(name, Regex(".*_P\\d{3}\\.jpg").matches(name))
            val after = controller.state.value as? PhotoState.Ready
            // Never a hang: without depth the shot is a normal photo and the source is dropped; report it clearly.
            val photoFile = find(name)
            assertTrue(
                "$source gave no depth on camera $cameraId: saved a normal photo (${photoFile != null}) and dropped the source",
                after?.portraitUnavailable != true
            )
            val photo = assertNotNull(find(name))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(photo).use { BitmapFactory.decodeStream(it, null, bounds) }
            Log.i(TAG, "saved $name ${bounds.outWidth}x${bounds.outHeight}")
            assertTrue("photo does not decode", bounds.outWidth > 0 && bounds.outHeight > 0)
            if (source != DepthSource.VENDOR_BOKEH) {
                assertEquals(ready.sizes.jpeg.width.toLong() * ready.sizes.jpeg.height, bounds.outWidth.toLong() * bounds.outHeight)
                val depth = assertNotNull(find(PhotoController.depthName(name)))
                context.contentResolver.delete(depth, null, null)
            }
            context.contentResolver.delete(photo, null, null)
        } finally {
            controller.shutdown()
        }
    }

    private fun assertNotNull(uri: Uri?): Uri {
        assertNotNull("file not found in MediaStore", uri)
        return requireNotNull(uri)
    }

    private fun find(displayName: String): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return null
    }

    private companion object {
        const val TAG = "PortraitCaptureTest"
        const val SETTLE_MS = 1_500L
        const val TAKE_TIMEOUT_MS = 120_000L
    }
}
