package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.CaptureSettings
import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertInfo
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertKind
import com.qtekfun.ultimatecamera.core.net.control.HealthDetail
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.control.StatusWarning
import com.qtekfun.ultimatecamera.core.net.control.ThermalLevel
import com.qtekfun.ultimatecamera.core.net.role.LinkState
import com.qtekfun.ultimatecamera.feature.camera.AlertLevel
import com.qtekfun.ultimatecamera.feature.camera.HealthAlert
import com.qtekfun.ultimatecamera.feature.camera.HealthKind
import com.qtekfun.ultimatecamera.feature.camera.ui.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCameraMapperTest {
    private val status = CameraStatus(
        bootId = "b",
        version = 3,
        recording = RecordingState.IDLE,
        batteryPercent = 64,
        thermal = ThermalLevel.MODERATE,
        freeSpaceMinutes = 95,
        profileId = "4k25",
        lutId = "rec709",
        capture = CaptureSettings(autoExposure = false, iso = 400, exposureTimeNanos = 20_000_000),
        health = HealthDetail(batteryTempTenthsC = 384, storageMinutes = 8),
        droppedFrames = 0,
        profiles = listOf(ChoiceInfo("4k25", "4K25 HLG", codec = "HEVC", fps = 25), ChoiceInfo("hd60", "1080p60", codec = "H.264", fps = 60)),
        luts = listOf(ChoiceInfo("rec709", "Rec.709"))
    )

    private fun snapshot(link: LinkState = LinkState.CONNECTED, s: CameraStatus? = status, ever: Boolean = true, intent: RecIntent? = null) =
        RemoteSnapshot("cam", "Cam A", link, s, ever, intent)

    @Test
    fun linkStatesFollowReconnection() {
        assertEquals(LinkUi.CONNECTING, RemoteCameraMapper.map(snapshot(LinkState.CONNECTING, null, ever = false)).link)
        assertEquals(LinkUi.LIVE, RemoteCameraMapper.map(snapshot()).link)
        val retrying = RemoteCameraMapper.map(snapshot(LinkState.WAITING))
        assertEquals(LinkUi.RETRYING, retrying.link)
        assertTrue("last known state is kept and marked", retrying.stale)
        assertEquals(64, retrying.batteryPercent)
        assertEquals(LinkUi.OFFLINE, RemoteCameraMapper.map(snapshot(LinkState.STOPPED)).link)
        assertFalse(RemoteCameraMapper.map(snapshot()).stale)
    }

    @Test
    fun readoutsAndTones() {
        val ui = RemoteCameraMapper.map(snapshot())
        assertEquals("4K25 HLG", ui.profileLabel)
        assertEquals("HEVC", ui.codec)
        assertEquals(25, ui.fps)
        assertEquals("health storage minutes win over the summary", 8, ui.storageMinutes)
        assertEquals(Tone.WARNING, ui.storageTone)
        assertEquals("38.4", ui.temperatureC)
        assertEquals(Tone.WARNING, ui.temperatureTone)
        assertEquals(Tone.NORMAL, ui.batteryTone)
        assertEquals(Tone.NORMAL, ui.droppedTone)
        assertEquals("1/50", ui.capture.shutter)
        assertEquals(Tone.ALERT, RemoteCameraMapper.map(snapshot(s = status.copy(droppedFrames = 2))).droppedTone)
        assertEquals(Tone.ALERT, RemoteCameraMapper.map(snapshot(s = status.copy(batteryPercent = 5))).batteryTone)
        assertEquals(Tone.NORMAL, RemoteCameraMapper.map(snapshot(s = status.copy(batteryPercent = 5, charging = true))).batteryTone)
        assertEquals(Tone.ALERT, RemoteCameraMapper.thermalTone(ThermalLevel.SEVERE))
    }

    @Test
    fun unknownProfileShowsItsIdAndDefaultRate() {
        val ui = RemoteCameraMapper.map(snapshot(s = status.copy(profileId = "custom", profiles = emptyList(), health = null)))
        assertEquals("custom", ui.profileLabel)
        assertEquals(RemoteCameraMapper.DEFAULT_FPS, ui.fps)
        assertEquals(95, ui.storageMinutes)
        assertNull(ui.temperatureC)
    }

    @Test
    fun healthAlertsBecomeTheCameraBannerAndCoverTheirWarnings() {
        val s = status.copy(
            warnings = setOf(StatusWarning.STORAGE_LOW, StatusWarning.CLOCK_NOT_SYNCED, StatusWarning.BATTERY_LOW),
            health = HealthDetail(alerts = listOf(HealthAlertInfo(HealthAlertKind.STORAGE, critical = true, minutes = 1)))
        )
        val ui = RemoteCameraMapper.map(snapshot(s = s))
        assertEquals(listOf(HealthAlert(HealthKind.STORAGE, AlertLevel.CRITICAL, 1)), ui.alerts)
        assertTrue(ui.hasCritical)
        assertEquals(listOf(StatusWarning.BATTERY_LOW, StatusWarning.CLOCK_NOT_SYNCED), ui.notices)
    }

    @Test
    fun recButtonIsOptimisticUntilTheCameraConfirms() {
        val start = RecIntent(start = true, commandId = "c1")
        assertEquals(RecUi.STARTING, RemoteCameraMapper.map(snapshot(intent = start)).rec)
        assertTrue(RemoteCameraMapper.map(snapshot(intent = start)).rec.showsRecording)
        val recording = status.copy(recording = RecordingState.RECORDING, recordingId = "t")
        assertEquals(RecUi.RECORDING, RemoteCameraMapper.map(snapshot(s = recording, intent = start)).rec)
        assertTrue(RemoteCameraMapper.intentReached(recording, start))
        val stop = RecIntent(start = false, commandId = "c2")
        assertEquals(RecUi.STOPPING, RemoteCameraMapper.map(snapshot(s = recording, intent = stop)).rec)
        assertFalse(RemoteCameraMapper.intentReached(recording, stop))
        assertTrue(RemoteCameraMapper.intentReached(status, stop))
        assertEquals(RecUi.SCHEDULED, RemoteCameraMapper.recUi(RecordingState.SCHEDULED, null))
        assertEquals(RecUi.ERROR, RemoteCameraMapper.recUi(RecordingState.ERROR, null))
    }

    @Test
    fun timecodeUsesTheCommonClockElseLocalObservation() {
        val start = 1_000_000_000_000L
        val recording = status.copy(recording = RecordingState.RECORDING, recordingStartCommonNanos = start)
        // 1 h 2 min 3 s and 520 ms at 25 fps: frame 13.
        val now = start + ((3_723L * 1000) + 520) * 1_000_000
        assertEquals("01:02:03:13", RemoteCameraMapper.map(snapshot(s = recording), nowCommonNanos = now).timecode)
        assertEquals("00:00:10:00", RemoteCameraMapper.map(snapshot(s = recording), recordingSeenSinceMs = 5_000, nowMs = 15_000).timecode)
        assertEquals("00:00:00:00", RemoteCameraMapper.map(snapshot(), nowCommonNanos = now).timecode)
    }

    @Test
    fun errorsArePassedForTranslation() {
        val ui = RemoteCameraMapper.map(snapshot().copy(lastError = ErrorCode.BUSY))
        assertEquals(ErrorCode.BUSY, ui.error)
    }

    @Test
    fun shutterText() {
        assertEquals("1/60", RemoteCameraMapper.shutterText(16_666_667))
        assertEquals("1/2000", RemoteCameraMapper.shutterText(500_000))
        assertEquals("1\"", RemoteCameraMapper.shutterText(1_000_000_000))
        assertEquals("0.5\"", RemoteCameraMapper.shutterText(500_000_000))
    }

    @Test
    fun criticalAlertsVibrateOncePerKind() {
        val tracker = CriticalAlertTracker()
        val calm = RemoteCameraMapper.map(snapshot())
        val critical = calm.copy(alerts = listOf(HealthAlert(HealthKind.BATTERY, AlertLevel.CRITICAL, 1)))
        assertFalse(tracker.update(listOf(calm)))
        assertTrue(tracker.update(listOf(critical)))
        assertFalse("still the same alert", tracker.update(listOf(critical)))
        assertTrue("another camera", tracker.update(listOf(critical, critical.copy(id = "cam2"))))
        assertFalse(tracker.update(listOf(calm)))
        assertTrue("again after it cleared", tracker.update(listOf(critical)))
    }

    @Test
    fun tileVideoOnlyWhereTheNetworkAllows() {
        val live = RemoteCameraMapper.map(snapshot())
        assertTrue(TileVideoPolicy.showVideo(true, 0, live, 20_000_000))
        assertFalse(TileVideoPolicy.showVideo(false, 0, live, 20_000_000))
        assertFalse(TileVideoPolicy.showVideo(true, 0, live, 400_000_000))
        assertFalse(TileVideoPolicy.showVideo(true, 4, live, null))
        assertFalse(TileVideoPolicy.showVideo(true, 0, RemoteCameraMapper.map(snapshot(LinkState.WAITING)), null))
    }

    @Test
    fun groupStartFallsBackToImmediate() {
        assertNull(GroupStartPlanner.plan(null, listOf(1), "r").startAtCommonNanos)
        assertNull(GroupStartPlanner.plan(SyncedStart.IMMEDIATE, listOf(1), "r").startAtCommonNanos)
        assertNull(GroupStartPlanner.plan({ error("no clock") }, listOf(1), "r").startAtCommonNanos)
        assertEquals(GroupStart("r", 42L), GroupStartPlanner.plan({ 42L }, listOf(1), "r"))
    }

    @Test
    fun commandsFromTheChips() {
        val c = RemoteCameraMapper.map(snapshot()).capture
        assertEquals(CommandBody.SetExposure(auto = false, iso = 400, exposureTimeNanos = 20_000_000), RemoteCommands.exposureLock(c, lock = true))
        assertEquals(CommandBody.SetExposure(auto = true), RemoteCommands.exposureLock(c, lock = false))
        assertEquals(CommandBody.SetExposure(auto = false, iso = 800, exposureTimeNanos = 20_000_000), RemoteCommands.iso(c, 800))
        assertEquals(CommandBody.SetExposure(auto = true, evCompensation = -1f), RemoteCommands.ev(-1f))
        assertEquals(CommandBody.SetWhiteBalance(auto = false, kelvin = 5600), RemoteCommands.whiteBalance(5600))
        assertEquals(CommandBody.SetFocus(auto = true), RemoteCommands.focus(null))
        assertEquals(listOf("HEVC", "H.264"), ProfileChoices.codecs(status.profiles))
        assertEquals(listOf("hd60"), ProfileChoices.profilesFor(status.profiles, "H.264").map { it.id })
        assertEquals("∞", RemoteCommands.focusText(0f))
        assertEquals("50 cm", RemoteCommands.focusText(2f))
        assertEquals("5 m", RemoteCommands.focusText(0.2f))
    }
}
