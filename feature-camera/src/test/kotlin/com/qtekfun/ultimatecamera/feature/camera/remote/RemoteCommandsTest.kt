package com.qtekfun.ultimatecamera.feature.camera.remote

import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.ControlRanges
import com.qtekfun.ultimatecamera.core.camera.LiveExposure
import com.qtekfun.ultimatecamera.core.camera.WhiteBalance
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.net.control.CaptureModeWire
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.feature.camera.CaptureMode
import com.qtekfun.ultimatecamera.feature.camera.MonitorLook
import com.qtekfun.ultimatecamera.feature.camera.RecordingLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandsTest {
    private val full = ControlRanges(
        isoRange = 50..6400,
        exposureTimeRangeNs = 100_000L..100_000_000L,
        evRange = -6..6,
        evStep = 1f / 3,
        minFocusDiopters = 10f,
        manualFocus = true,
        kelvinRange = 2000..10000
    )
    private val basic = ControlRanges()

    private fun controls(body: CommandBody, current: CameraControls = CameraControls(), ranges: ControlRanges? = full): CameraControls =
        (RemoteCommands.map(body, current, ranges) as RemoteAction.Controls).controls

    @Test
    fun exposureManualAndAutoWithEv() {
        val manual = controls(CommandBody.SetExposure(auto = false, iso = 800, exposureTimeNanos = 10_000_000))
        assertTrue(manual.manualExposure)
        assertEquals(800, manual.iso)
        assertEquals(10_000_000L, manual.exposureTimeNs)

        val auto = controls(CommandBody.SetExposure(auto = true, evCompensation = 1f), current = manual)
        assertFalse(auto.manualExposure)
        assertEquals(3, auto.exposureCompensation) // +1 EV in 1/3 steps
        assertEquals(6, controls(CommandBody.SetExposure(auto = true, evCompensation = 5f)).exposureCompensation) // clamped
        assertEquals(2, controls(CommandBody.SetExposure(auto = true), current = CameraControls(exposureCompensation = 2)).exposureCompensation)
    }

    @Test
    fun unsupportedOnCamerasWithoutManualControls() {
        assertTrue(
            RemoteCommands.map(CommandBody.SetExposure(auto = false, iso = 100, exposureTimeNanos = 1), CameraControls(), basic) is RemoteAction.Unsupported
        )
        assertTrue(RemoteCommands.map(CommandBody.SetFocus(auto = false, distanceDiopters = 1f), CameraControls(), basic) is RemoteAction.Unsupported)
        assertTrue(RemoteCommands.map(CommandBody.SetWhiteBalance(auto = false, kelvin = 5600), CameraControls(), basic) is RemoteAction.Unsupported)
        // Auto is always possible.
        assertTrue(RemoteCommands.map(CommandBody.SetFocus(auto = true), CameraControls(), basic) is RemoteAction.Controls)
    }

    @Test
    fun focusWhiteBalanceAndLocks() {
        val focus = controls(CommandBody.SetFocus(auto = false, distanceDiopters = 2f))
        assertTrue(focus.manualFocus)
        assertEquals(2f, focus.focusDiopters)
        val wb = controls(CommandBody.SetWhiteBalance(auto = false, kelvin = 3200))
        assertEquals(WhiteBalance.MANUAL, wb.whiteBalance)
        assertEquals(3200, wb.kelvin)
        assertEquals(WhiteBalance.AUTO, controls(CommandBody.SetWhiteBalance(auto = true), current = wb).whiteBalance)
        val locks = controls(CommandBody.SetLocks(aeLock = true), current = CameraControls(awbLock = true))
        assertTrue(locks.aeLock)
        assertTrue(locks.awbLock) // null leaves it
    }

    @Test
    fun profileIdsRoundTrip() {
        val profile = RecordingProfile(3840, 2160, 60, VideoCodec.HEVC, tenBit = true)
        val key = ProfileKey.of(profile)
        assertEquals("3840x2160@60-10-HEVC", key.id)
        assertEquals(key, ProfileKey.parse(key.id))
        assertTrue(key.matches(profile))
        assertEquals(ProfileKey(1920, 1080, 30, false, VideoCodec.H264), ProfileKey.parse("1920x1080@30-8-H264"))
        assertNull(ProfileKey.parse("4k60"))
        assertNull(ProfileKey.parse("1920x1080@30-8-VP9"))
        assertTrue(RemoteCommands.map(CommandBody.SelectProfile("nonsense"), CameraControls(), full) is RemoteAction.Invalid)
        assertEquals(RemoteAction.Profile(key), RemoteCommands.map(CommandBody.SelectProfile(key.id), CameraControls(), full))
    }

    @Test
    fun looksModesAndSlave() {
        assertEquals(RemoteAction.Recording(RecordingLook.UcLog), RemoteCommands.map(CommandBody.SelectLut("uclog"), CameraControls(), full))
        assertEquals(RemoteAction.Recording(RecordingLook.None), RemoteCommands.map(CommandBody.SelectLut(null), CameraControls(), full))
        assertEquals(RemoteAction.Recording(RecordingLook.Library("abc")), RemoteCommands.map(CommandBody.SelectLut("lib:abc"), CameraControls(), full))
        assertTrue(RemoteCommands.map(CommandBody.SelectLut("rec709"), CameraControls(), full) is RemoteAction.Invalid)
        assertEquals(RemoteAction.Monitor(MonitorLook.UcLogToRec709), RemoteCommands.map(CommandBody.SetMonitorLook("uclog709"), CameraControls(), full))
        assertEquals(RemoteAction.Monitor(MonitorLook.Auto), RemoteCommands.map(CommandBody.SetMonitorLook(null), CameraControls(), full))
        assertEquals(RemoteAction.Mode(CaptureMode.PHOTO), RemoteCommands.map(CommandBody.SetCaptureMode(CaptureModeWire.PHOTO), CameraControls(), full))
        assertEquals(RemoteAction.Slave(true), RemoteCommands.map(CommandBody.SetSlaveMode(true), CameraControls(), full))
        assertTrue(RemoteCommands.map(CommandBody.StartRecording("t"), CameraControls(), full) is RemoteAction.Invalid)
    }

    @Test
    fun statusShowsLiveValuesAndLocks() {
        val s = RemoteCommands.captureSettings(
            CameraControls(exposureCompensation = -3, aeLock = true),
            LiveExposure(iso = 400, exposureTimeNs = 8_000_000),
            full
        )
        assertTrue(s.autoExposure)
        assertEquals(400, s.iso)
        assertEquals(8_000_000L, s.exposureTimeNanos)
        assertEquals(-1f, s.evCompensation!!, 1e-4f)
        assertTrue(s.aeLock)
        assertNull(s.whiteBalanceKelvin)
    }
}
