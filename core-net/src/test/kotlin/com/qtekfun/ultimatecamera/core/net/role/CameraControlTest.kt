package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.control.ByeReason
import com.qtekfun.ultimatecamera.core.net.control.ClipInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertInfo
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertKind
import com.qtekfun.ultimatecamera.core.net.control.HealthDetail
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.control.StatusWarning
import com.qtekfun.ultimatecamera.core.net.control.ThermalLevel
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlTest {
    private fun ok(result: CommandResult) = result.outcome as CommandResult.Outcome.Ok

    private fun failed(result: CommandResult) = result.outcome as CommandResult.Outcome.Failed

    @Test
    fun connectResyncsStatusThenStartsAndStopsRecording() {
        val w = Wire()
        assertTrue(w.connect())
        w.pump()
        assertEquals(LinkState.CONNECTED, w.link.state)
        assertEquals(RecordingState.IDLE, w.link.status?.recording) // full status on connect

        val start = w.link.submit(CommandBody.StartRecording("take-1"))
        w.pump()
        assertTrue(ok(w.events.result(start)).detail?.atCommonNanos != null)
        assertEquals(listOf("take-1"), w.actions.started)
        assertEquals(RecordingState.RECORDING, w.link.status?.recording)
        assertEquals("take-1", w.link.status?.recordingId)

        val stop = w.link.submit(CommandBody.StopRecording("take-1"))
        w.pump()
        assertFalse(ok(w.events.result(stop)).duplicate)
        assertEquals(listOf("take-1"), w.actions.stopped)
        assertEquals(RecordingState.IDLE, w.link.status?.recording)
        assertEquals(0, w.link.pendingCommands)
    }

    @Test
    fun linkLossNeverStopsRecordingAndStateComesBackOnReconnect() {
        val w = Wire()
        w.connect()
        w.link.submit(CommandBody.StartRecording("take-1"))
        w.pump()
        w.drop()
        assertEquals(LinkState.WAITING, w.link.state)
        assertEquals(RecordingState.RECORDING, w.camera.status.recording) // REQ-NET-007
        assertTrue(w.camera.connectedPeers.isEmpty())
        // While the link is down the camera's battery drops; the controller learns it on reconnect.
        w.camera.updateHealth(DeviceHealth(batteryPercent = 9))
        w.run(ms = 1_000)
        assertEquals(LinkState.CONNECTED, w.link.state)
        assertEquals(RecordingState.RECORDING, w.link.status?.recording)
        assertEquals(9, w.link.status?.batteryPercent)
        assertTrue(StatusWarning.BATTERY_LOW in w.link.status!!.warnings)
        assertEquals(listOf("take-1"), w.actions.started)
        assertTrue(w.actions.stopped.isEmpty())
    }

    @Test
    fun commandsSubmittedWhileDisconnectedAreDeliveredAfterReconnecting() {
        val w = Wire()
        val id = w.link.submit(CommandBody.StartRecording("take-1")) // never connected yet
        assertEquals(1, w.link.pendingCommands)
        w.run(ms = 200)
        assertTrue(ok(w.events.result(id)).let { !it.duplicate })
        assertEquals(listOf("take-1"), w.actions.started)
    }

    @Test
    fun lostAckIsResolvedByReplayWithoutASecondTake() {
        val w = Wire()
        w.connect()
        w.pump()
        val id = w.link.submit(CommandBody.StartRecording("take-1"))
        // The command reaches the camera, the ack is lost with the link.
        w.camera.onMessage(Wire.CONTROLLER_ID, Decoded.Message(w.toCamera.queue.removeFirst()))
        w.drop()
        assertEquals(1, w.link.pendingCommands)
        w.run(ms = 1_000)
        assertTrue(ok(w.events.result(id)).duplicate) // the replay was recognised
        assertEquals(listOf("take-1"), w.actions.started)
        assertEquals(1, w.events.results.size)
    }

    @Test
    fun duplicatedAndReorderedMessagesAreHarmless() {
        val w = Wire()
        w.connect()
        w.pump()
        val start = w.link.submit(CommandBody.StartRecording("take-1"))
        val command = w.toCamera.queue.removeFirst()
        repeat(3) { w.camera.onMessage(Wire.CONTROLLER_ID, Decoded.Message(command)) } // duplicated in transit
        w.pump()
        assertEquals(listOf("take-1"), w.actions.started)
        assertEquals(1, w.events.results.count { it.commandId == start })
        // Duplicate acks reach the link; only the first counts.
        assertEquals(0, w.link.pendingCommands)

        // An old status arriving late does not overwrite the newer one.
        val newest = w.link.status!!
        w.link.onMessage(Decoded.Message(ControlMessage.Status(newest.copy(version = newest.version - 1, recording = RecordingState.IDLE))))
        assertEquals(newest, w.link.status)

        // Stop, then a stale start for the same take (a new command id, as a confused peer would send): no new take.
        w.link.submit(CommandBody.StopRecording())
        w.pump()
        val stale = w.link.submit(CommandBody.StartRecording("take-1"))
        w.pump()
        assertTrue(ok(w.events.result(stale)).duplicate)
        assertEquals(listOf("take-1"), w.actions.started)
        assertEquals(RecordingState.IDLE, w.camera.status.recording)

        // Stopping again is fine too.
        val again = w.link.submit(CommandBody.StopRecording("take-1"))
        w.pump()
        assertTrue(ok(w.events.result(again)).duplicate)
    }

    @Test
    fun scheduledStartHappensAtTheCommonTimeOnTheCameraClock() {
        val offset = 7_000 * MS // common = camera local + 7 s
        val w = Wire(cameraClock = FakeCommonClock(offset))
        w.connect()
        w.pump()
        val at = w.clock.now + offset + 500 * MS
        val id = w.link.submit(CommandBody.StartRecording("take-1", at))
        w.pump()
        assertEquals(at, ok(w.events.result(id)).detail?.atCommonNanos)
        assertEquals(RecordingState.SCHEDULED, w.camera.status.recording)
        assertEquals(at - offset, w.camera.nextDeadline)
        w.run(ms = 450, stepMs = 10)
        assertTrue(w.actions.started.isEmpty())
        w.run(ms = 50, stepMs = 10)
        assertEquals(listOf("take-1"), w.actions.started)
        assertEquals(RecordingState.RECORDING, w.link.status?.recording)
        assertEquals(at, w.link.status?.recordingStartCommonNanos)
    }

    @Test
    fun scheduledStartIsCancelledByAStop() {
        val w = Wire()
        w.connect()
        w.link.submit(CommandBody.StartRecording("take-1", w.clock.now + 2_000 * MS))
        w.pump()
        w.link.submit(CommandBody.StopRecording())
        w.pump()
        w.run(ms = 3_000)
        assertTrue(w.actions.started.isEmpty())
        assertEquals(RecordingState.IDLE, w.camera.status.recording)
    }

    @Test
    fun lateOrUnsyncedStarts() {
        val w = Wire()
        w.connect()
        w.pump()
        val late = w.link.submit(CommandBody.StartRecording("take-1", w.clock.now - 40 * MS))
        w.pump()
        assertEquals(40_000L, ok(w.events.result(late)).detail?.lateMicros)
        assertEquals(listOf("take-1"), w.actions.started)

        val busy = w.link.submit(CommandBody.StartRecording("take-2"))
        w.pump()
        assertEquals(ErrorCode.ALREADY_RECORDING, failed(w.events.result(busy)).code)

        val u = Wire(cameraClock = FakeCommonClock(offset = null))
        u.connect()
        val unsynced = u.link.submit(CommandBody.StartRecording("take-1", u.clock.now))
        u.pump()
        assertEquals(ErrorCode.CLOCK_NOT_SYNCED, failed(u.events.result(unsynced)).code)
        val tooFar = u.link.submit(CommandBody.StartRecording("take-1"))
        u.pump()
        assertTrue(u.events.result(tooFar).outcome is CommandResult.Outcome.Ok) // an immediate start needs no clock
    }

    @Test
    fun heartbeatTimeoutRedialsAndTheCameraKeepsRecording() {
        val w = Wire()
        w.connect()
        w.link.submit(CommandBody.StartRecording("take-1"))
        w.pump()
        // Half-open link: nothing gets through any more, but no read error is reported.
        w.toCamera.broken = true
        w.toController.broken = true
        w.run(ms = 4_500, reconnect = false)
        assertEquals(LinkState.WAITING, w.link.state)
        assertTrue(w.camera.connectedPeers.isEmpty())
        assertEquals(RecordingState.RECORDING, w.camera.status.recording)
        w.run(ms = 1_000)
        assertEquals(LinkState.CONNECTED, w.link.state)
        assertTrue(w.link.rttNanos != null || w.link.status != null)
    }

    @Test
    fun heartbeatsMeasureTheRoundTrip() {
        val w = Wire()
        w.connect()
        w.run(ms = 3_000)
        assertTrue("rtt ${w.link.rttNanos}", w.link.rttNanos != null && w.link.rttNanos!! <= 1_000 * MS)
    }

    @Test
    fun unansweredCommandsExpire() {
        val w = Wire(timing = LinkTiming(commandLifetimeNanos = 2_000 * MS))
        val id = w.link.submit(CommandBody.SelectLut("rec709"))
        w.run(ms = 2_100, reconnect = false)
        assertEquals(ErrorCode.TIMED_OUT, failed(w.events.result(id)).code)
        assertEquals(0, w.link.pendingCommands)
    }

    @Test
    fun unpairedControllerStopsRedialling() {
        val w = Wire()
        w.connect()
        w.pump()
        w.camera.unpair(Wire.CONTROLLER_ID)
        w.pump()
        assertEquals(LinkState.STOPPED, w.link.state)
        w.run(ms = 10_000)
        assertEquals(LinkState.STOPPED, w.link.state)
        assertTrue(w.camera.connectedPeers.isEmpty())
    }

    @Test
    fun backoffGrowsWithJitterAndIsCapped() {
        val b = Backoff(baseNanos = 250 * MS, maxNanos = 5_000 * MS, random = Random(3))
        for (attempt in 0..30) {
            val d = b.delayNanos(attempt)
            val ceiling = minOf(5_000 * MS, (250 * MS) shl minOf(attempt, 20))
            assertTrue("attempt $attempt: $d", d in ceiling / 2..ceiling)
        }
    }

    @Test
    fun settingsAreValidatedAppliedAndReflected() {
        val w = Wire()
        w.connect()
        w.pump()
        val iso = w.link.submit(CommandBody.SetExposure(auto = false, iso = 800, exposureTimeNanos = 10 * MS))
        val badIso = w.link.submit(CommandBody.SetExposure(auto = false, iso = 0, exposureTimeNanos = 10 * MS))
        val kelvin = w.link.submit(CommandBody.SetWhiteBalance(auto = false, kelvin = 5_600))
        val focus = w.link.submit(CommandBody.SetFocus(auto = false))
        w.pump()
        assertTrue(w.events.result(iso).outcome is CommandResult.Outcome.Ok)
        assertEquals(ErrorCode.INVALID_ARGUMENT, failed(w.events.result(badIso)).code)
        assertTrue(w.events.result(kelvin).outcome is CommandResult.Outcome.Ok)
        assertEquals(ErrorCode.INVALID_ARGUMENT, failed(w.events.result(focus)).code)
        assertEquals(800, w.link.status?.capture?.iso)
        assertEquals(5_600, w.link.status?.capture?.whiteBalanceKelvin)
        assertEquals(2, w.actions.settings.size)

        w.link.submit(CommandBody.StartRecording("take-1"))
        val profile = w.link.submit(CommandBody.SelectProfile("4k60"))
        val lut = w.link.submit(CommandBody.SelectLut("rec709"))
        w.pump()
        assertEquals(ErrorCode.BUSY, failed(w.events.result(profile)).code) // no profile change mid-take
        assertTrue(w.events.result(lut).outcome is CommandResult.Outcome.Ok)
        assertEquals("rec709", w.link.status?.lutId)
    }

    @Test
    fun unknownMessagesAndStartFailures() {
        val w = Wire()
        w.connect()
        w.pump()
        w.camera.onMessage(Wire.CONTROLLER_ID, Decoded.Unknown("set_zoom"))
        val error = w.toController.queue.last() as ControlMessage.Error
        assertEquals(ErrorCode.UNKNOWN_MESSAGE, error.code)
        assertNull(error.commandId)
        w.pump()

        w.actions.failStart = ErrorCode.CAMERA_ERROR
        val id = w.link.submit(CommandBody.StartRecording("take-1"))
        w.pump()
        assertEquals(ErrorCode.CAMERA_ERROR, failed(w.events.result(id)).code)
        assertEquals(RecordingState.ERROR, w.link.status?.recording)
        assertTrue(StatusWarning.RECORDING_ERROR in w.link.status!!.warnings)
    }

    @Test
    fun warningsFollowHealth() {
        val w = Wire()
        w.camera.updateHealth(DeviceHealth(batteryPercent = 50, thermal = ThermalLevel.SEVERE, freeSpaceMinutes = 8))
        assertEquals(setOf(StatusWarning.THERMAL, StatusWarning.STORAGE_LOW), w.camera.status.warnings)
        w.camera.updateHealth(DeviceHealth(batteryPercent = 10, charging = true, freeSpaceMinutes = 0))
        assertEquals(setOf(StatusWarning.STORAGE_LOW), w.camera.status.warnings)
        w.connect()
        val full = w.link.submit(CommandBody.StartRecording("take-1"))
        w.pump()
        assertEquals(ErrorCode.STORAGE_FULL, failed(w.events.result(full)).code)
    }

    @Test
    fun healthDetailReachesControllers() {
        val w = Wire()
        w.connect()
        w.pump()
        val detail = HealthDetail(batteryMinutes = 8, alerts = listOf(HealthAlertInfo(HealthAlertKind.BATTERY, minutes = 8)))
        w.camera.updateHealth(DeviceHealth(batteryPercent = 20, detail = detail))
        w.pump()
        assertEquals(detail, w.link.status?.health)
    }

    @Test
    fun finishedClipsReachControllersOnceEvenAcrossReconnects() {
        val w = Wire()
        w.connect()
        w.pump()
        val clip = ClipInfo("take-1", "UC_0001.mp4", 1L, 10_000, 1_000_000)
        w.camera.onRecordingFinished(clip)
        w.pump()
        w.drop()
        w.run(ms = 1_000) // the reconnect resends recent clips
        assertEquals(listOf(clip), w.events.clips)
    }

    @Test
    fun aSecondConnectionOfTheSamePeerReplacesTheFirst() {
        val w = Wire()
        w.connect()
        w.pump()
        val old = w.toController
        val fresh = FakeSink()
        w.camera.onPeerConnected(Wire.CONTROLLER_ID, DeviceRole.MONITOR, fresh)
        assertTrue(old.closed)
        assertEquals(ControlMessage.Bye(ByeReason.REPLACED), old.queue.last())
        w.camera.onPeerDisconnected(Wire.CONTROLLER_ID, old) // late report about the old one: ignored
        assertEquals(setOf(Wire.CONTROLLER_ID), w.camera.connectedPeers)
        // Cameras do not take connections from cameras.
        val cam = FakeSink()
        w.camera.onPeerConnected("other-camera", DeviceRole.CAMERA, cam)
        assertTrue(cam.closed)
    }

    @Test
    fun clockSourcePrefersTheMaster() {
        val sources = mutableListOf<String?>()
        val clock = FakeClock()
        val camera = CameraNode("boot", clock, FakeCommonClock(), FakeActions(), onClockSource = { sources += it })
        val monitor = FakeSink()
        val master = FakeSink()
        camera.onPeerConnected("monitor", DeviceRole.MONITOR, monitor)
        camera.onPeerConnected("master", DeviceRole.MASTER, master)
        camera.onPeerDisconnected("master", master)
        camera.onPeerDisconnected("monitor", monitor)
        assertEquals(listOf("monitor", "master", "monitor", null), sources)
    }
}
