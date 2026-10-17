package com.qtekfun.ultimatecamera.feature.camera.remote

import com.qtekfun.ultimatecamera.core.gpu.gl.StreamOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorStreamSpecTest {
    @Test
    fun aRegularPhoneSends960At30() {
        assertEquals(MonitorStreamSpec(960, 540, 30, 1_500_000), MonitorStreamSpec.of(3840, 2160, modestPhone = false))
        assertEquals(MonitorStreamSpec(960, 720, 30, 1_500_000), MonitorStreamSpec.of(1440, 1080, modestPhone = false))
    }

    @Test
    fun aModestPhoneSendsLessSoTheTakeKeepsItsRate() {
        assertEquals(MonitorStreamSpec(640, 360, 15, 700_000), MonitorStreamSpec.of(1920, 1080, modestPhone = true))
        // A small take is never scaled up.
        assertEquals(480, MonitorStreamSpec.of(480, 270, modestPhone = true).width)
    }

    @Test
    fun theRendererThinsTheCameraRateToTheStreamCap() {
        assertEquals(1, StreamOutput.everyNth(30))
        assertEquals(2, StreamOutput.everyNth(60))
        assertEquals(2, StreamOutput.everyNth(30, maxFps = 15))
        assertEquals(4, StreamOutput.everyNth(60, maxFps = 15))
        assertEquals(2, StreamOutput.everyNth(24, maxFps = 15))
    }
}
