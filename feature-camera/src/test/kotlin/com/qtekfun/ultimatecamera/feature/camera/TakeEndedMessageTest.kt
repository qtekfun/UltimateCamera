package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.recording.WavReport
import org.junit.Assert.assertEquals
import org.junit.Test

class TakeEndedMessageTest {
    private val gaps = WavReport(droppedFrames = 72_000, overruns = 1, sampleRate = 48_000, error = null, kept = true)

    @Test
    fun aCleanTakeIsSaved() {
        assertEquals(CameraMessage.Saved("a.mp4"), CameraMessage.takeEnded("a.mp4", null, null))
        assertEquals(CameraMessage.Saved("a.mp4"), CameraMessage.takeEnded("a.mp4", null, gaps.copy(droppedFrames = 0)))
    }

    @Test
    fun aWavWithSilenceFromASlowDriveIsToldWithItsSeconds() {
        assertEquals(CameraMessage.WavGaps("a.mp4", 1.5), CameraMessage.takeEnded("a.mp4", null, gaps))
    }

    @Test
    fun aProblemOfTheTakeComesFirstAndADeletedWavSaysNothing() {
        assertEquals(CameraMessage.Problem("codec"), CameraMessage.takeEnded("a.mp4", "codec", gaps))
        assertEquals(CameraMessage.Saved("a.mp4"), CameraMessage.takeEnded("a.mp4", null, gaps.copy(kept = false)))
    }
}
