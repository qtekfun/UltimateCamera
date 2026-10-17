package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.codec.ColorTag
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeRulesTest {
    @Test
    fun backCamerasTurnAgainstTheDisplayFrontCamerasWithIt() {
        // Typical back sensor at 90°: portrait needs 90, landscape left 0, landscape right 180.
        assertEquals(90, TakeRules.uprightRotation(90, front = false, displayRotationDegrees = 0))
        assertEquals(0, TakeRules.uprightRotation(90, front = false, displayRotationDegrees = 90))
        assertEquals(180, TakeRules.uprightRotation(90, front = false, displayRotationDegrees = 270))
        // Typical front sensor at 270.
        assertEquals(270, TakeRules.uprightRotation(270, front = true, displayRotationDegrees = 0))
        assertEquals(0, TakeRules.uprightRotation(270, front = true, displayRotationDegrees = 90))
        assertEquals(180, TakeRules.uprightRotation(270, front = true, displayRotationDegrees = 270))
        // Always in 0..359.
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                for (front in listOf(true, false)) assertTrue(TakeRules.uprightRotation(sensor, front, display) in 0 until 360)
            }
        }
    }

    @Test
    fun previewRotationCompensatesTheDisplay() {
        assertEquals(90, TakeRules.previewRotationDegrees(90, 0))
        assertEquals(0, TakeRules.previewRotationDegrees(90, 90))
        assertEquals(180, TakeRules.previewRotationDegrees(90, 270))
        assertEquals(0, TakeRules.previewRotationDegrees(0, 0))
    }

    @Test
    fun mirroredTouchesAreFlipped() {
        assertEquals(100f, TakeRules.unmirroredX(100f, 1080, mirrored = false))
        assertEquals(980f, TakeRules.unmirroredX(100f, 1080, mirrored = true))
    }

    @Test
    fun scheduledStartTiming() {
        val now = 5_000_000_000L
        assertEquals(now, TakeRules.startInstant(now, null))
        assertEquals(now + 200_000_000L, TakeRules.startInstant(now, now + 200_000_000L))
        assertEquals("a start already past begins now", now, TakeRules.startInstant(now, now - 1))
        assertEquals(0L, TakeRules.lateness(now, null))
        assertEquals(0L, TakeRules.lateness(now, now + 1))
        assertEquals(30_000_000L, TakeRules.lateness(now, now - 30_000_000L))
        // The running time of a scheduled take counts from its start instant.
        assertEquals(10_200L, TakeRules.startedAtElapsedMs(10_000L, now + 200_000_000L, now))
        assertEquals(10_000L, TakeRules.startedAtElapsedMs(10_000L, now, now))
    }

    @Test
    fun aPartIsKeptOnlyWithFrames() {
        assertTrue(TakeRules.usable(writerStarted = true, encodedFrames = 1))
        assertFalse(TakeRules.usable(writerStarted = true, encodedFrames = 0))
        assertFalse(TakeRules.usable(writerStarted = false, encodedFrames = 30))
    }

    @Test
    fun partProblemsInPriorityOrder() {
        assertNull(TakeRules.partProblem(null, null, null, null, usable = true))
        assertEquals("nothing was recorded", TakeRules.partProblem(null, null, null, null, usable = false))
        assertEquals("codec error", TakeRules.partProblem("codec error", "mic", "muxer", "disk", usable = true))
        assertEquals("mic", TakeRules.partProblem(null, "mic", "muxer", "disk", usable = true))
        assertEquals("muxer", TakeRules.partProblem(null, null, "muxer", "disk", usable = false))
        assertEquals("WAV: disk full", TakeRules.partProblem(null, null, null, "disk full", usable = true))
    }

    @Test
    fun theProxyNeverHidesAProblemOfTheTake() {
        assertEquals("take", TakeRules.takeProblem("take", "proxy"))
        assertEquals("proxy", TakeRules.takeProblem(null, "proxy"))
        assertNull(TakeRules.takeProblem(null, null))
    }

    @Test
    fun directTakesAreTaggedAsTheCameraDelivers() {
        assertEquals(ColorTag.HLG_BT2020, DirectPath.colorTag(RecordingProfile(7680, 4320, 30)))
        assertNull(DirectPath.colorTag(RecordingProfile(7680, 4320, 30, codec = VideoCodec.H264, tenBit = false)))
    }

    @Test
    fun vendorSessionOnlyForHeldModes() {
        val session = VendorSession(emptyList(), sessionType = 0xF002, held = listOf(HeldMode(3840, 2160, 30), HeldMode(1920, 1080, 60)))
        assertTrue(session.allows(3840, 2160, 30))
        assertTrue("a lower rate of a held size is held too", session.allows(3840, 2160, 24))
        assertFalse(session.allows(3840, 2160, 60))
        assertTrue(session.allows(1920, 1080, 60))
        assertFalse(session.allows(2560, 1440, 30))
        assertFalse(VendorSession(emptyList()).allows(1920, 1080, 30))
    }

    @Test
    fun hdrPreviewStateIsOffWithoutARenderer() {
        val state = PreviewHdrState()
        state.allowed = true
        state.active.value = true
        state.update(null, tenBit = true, setup = ColorSetup.NONE)
        assertFalse("no renderer cannot tag the preview", state.active.value)
        state.update(null, tenBit = null, setup = ColorSetup.NONE)
        assertFalse(state.active.value)
    }
}
