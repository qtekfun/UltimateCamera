package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.WhiteBalance
import com.qtekfun.ultimatecamera.core.gpu.SceneTintMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneTintModeTest {
    @Test
    fun theGpuCorrectsOnlyAutoAndOnlyWhereTheCameraCannot() {
        assertEquals(SceneTintMode.LIVE, CameraController.sceneTintMode(false, CameraControls()))
        assertEquals(SceneTintMode.HOLD, CameraController.sceneTintMode(false, CameraControls(awbLock = true)))
        assertEquals(SceneTintMode.OFF, CameraController.sceneTintMode(true, CameraControls()))
        assertEquals(SceneTintMode.OFF, CameraController.sceneTintMode(false, CameraControls(whiteBalance = WhiteBalance.DAYLIGHT)))
        assertEquals(SceneTintMode.OFF, CameraController.sceneTintMode(false, CameraControls(whiteBalance = WhiteBalance.MANUAL)))
    }
}
