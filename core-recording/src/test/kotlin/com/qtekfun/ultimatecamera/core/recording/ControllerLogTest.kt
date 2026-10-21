package com.qtekfun.ultimatecamera.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ControllerLogTest {
    @Test
    fun `each screen state is one line naming the mode and the stream`() {
        val upscaled = RecordingProfile(3840, 2160, 30, tenBit = false, sourceWidth = 3264, sourceHeight = 1836)
        assertEquals("previewing 4K30 ↑ stream 3264x1836", ControllerLog.line(CameraState.Previewing(upscaled)))
        assertEquals("recording 4K30 ↑", ControllerLog.line(CameraState.Recording(upscaled, 0L, "take.mp4")))
        assertEquals("failed rejected=true no 10-bit", ControllerLog.line(CameraState.Failed("no 10-bit", rejected = true)))
        assertEquals("closed", ControllerLog.line(CameraState.Closed))
        assertSame(CameraState.Closed, ControllerLog.state(CameraState.Closed))
    }
}
