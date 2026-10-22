package com.qtekfun.ultimatecamera.core.gpu.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTurnTest {
    @Test
    fun backCameraTurnsAgainstTheDisplay() {
        assertEquals(0, PreviewTurn.degrees(0, front = false))
        assertEquals(270, PreviewTurn.degrees(90, front = false))
        assertEquals(180, PreviewTurn.degrees(180, front = false))
        assertEquals(90, PreviewTurn.degrees(270, front = false))
    }

    /** D101: the selfie preview turned like the back camera's and showed upside down in both landscapes. */
    @Test
    fun frontCameraTurnsWithTheDisplay() {
        assertEquals(0, PreviewTurn.degrees(0, front = true))
        assertEquals(90, PreviewTurn.degrees(90, front = true))
        assertEquals(180, PreviewTurn.degrees(180, front = true))
        assertEquals(270, PreviewTurn.degrees(270, front = true))
    }

    /**
     * The sensor turn of the SurfaceTexture plus the preview turn is the turn that makes the recorded frame upright (the
     * MP4 orientation hint: front cameras sensor + display, back cameras sensor − display), so preview and file agree.
     */
    @Test
    fun previewAgreesWithTheUprightRecording() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                assertEquals((sensor + display) % 360, (sensor + PreviewTurn.degrees(display, front = true)) % 360)
                assertEquals((sensor - display + 360) % 360, (sensor + PreviewTurn.degrees(display, front = false)) % 360)
            }
        }
    }
}
