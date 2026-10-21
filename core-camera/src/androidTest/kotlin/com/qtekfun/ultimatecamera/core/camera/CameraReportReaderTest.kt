package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** D111: the camera report reads every listed camera on a real phone without opening any. */
@RunWith(AndroidJUnit4::class)
class CameraReportReaderTest {
    @Test
    fun everyListedCameraIsInTheReportWithItsTextureSizes() {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(CameraManager::class.java)
        val entries = CameraReportReader(manager).read()
        val listed = manager.cameraIdList.toList()
        assertTrue(entries.filter { it.origin == "listed" }.map { it.id } == listed)
        assertTrue(entries.filter { it.origin == "listed" }.all { "PRIVATE" in it.sizes })
        CameraReportText.format(entries).lines().forEach { Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "UCREPORT"
    }
}
