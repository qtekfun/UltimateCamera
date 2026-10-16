package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.FrameRate
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.clock.SourceClock
import com.qtekfun.ultimatecamera.core.net.clock.Timecode
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterMonitorTest {
    /** A phone's own monotonic clock: real time plus a per-phone origin. */
    private class PhoneClock(private val world: FakeClock, private val origin: Long) : MonotonicClock {
        override fun nanos(): Long = world.now + origin
    }

    /** Records the world time at which each take really started. */
    private class TimedActions(private val world: FakeClock) : CameraActions {
        val startedAt = mutableMapOf<String, Long>()

        override fun startRecording(recordingId: String): ActionResult {
            startedAt[recordingId] = world.now
            return ActionResult.Ok
        }

        override fun stopRecording(recordingId: String) = ActionResult.Ok

        override fun applySetting(command: CommandBody) = ActionResult.Ok
    }

    /** Created at world time 0, when the master's common clock reads [epoch]. */
    private class Camera(world: FakeClock, origin: Long, epoch: Long, val id: String) {
        val clock = PhoneClock(world, origin)
        val actions = TimedActions(world)

        // Its synced view of the master's clock (exact here): common = world + epoch = local − origin + epoch.
        val common = FakeCommonClock(offset = epoch - origin)
        val node = CameraNode("boot-$id", clock, common, actions)
        var toCamera = FakeSink()
        var toMaster = FakeSink()
    }

    @Test
    fun masterStartsEveryCameraAtTheSameInstantOnAFrameBoundary() {
        val world = FakeClock(now = 0)
        val masterClock = PhoneClock(world, origin = 5_000 * MS)
        val epoch = 1_792_195_200_000 * MS + 37_123 * MS
        val source = SourceClock(masterClock, epochNanosAtStart = epoch)
        val cameras = listOf(Camera(world, 123_456 * MS, epoch, "a"), Camera(world, 9 * MS, epoch, "b"))
        val listeners = cameras.associate { it.id to RecordingListener() }
        val master = MasterNode(masterClock, source, { id -> ControllerLink(id, masterClock, listeners.getValue(id), backoff = Backoff(random = Random(1))) })
        cameras.forEach { master.addCamera(it.id) }

        fun step(ms: Long) {
            repeat((ms / 5).toInt()) {
                world.advance(5)
                master.tick()
                for (c in cameras) {
                    c.node.tick()
                    val link = master.link(c.id)!!
                    if (link.pollDial()) {
                        c.toCamera = FakeSink()
                        c.toMaster = FakeSink()
                        c.node.onPeerConnected("master", DeviceRole.MASTER, c.toMaster)
                        link.onConnected(c.toCamera)
                    }
                    while (c.toCamera.queue.isNotEmpty() || c.toMaster.queue.isNotEmpty()) {
                        while (c.toCamera.queue.isNotEmpty()) c.node.onMessage("master", Decoded.Message(c.toCamera.queue.removeFirst()))
                        while (c.toMaster.queue.isNotEmpty()) link.onMessage(Decoded.Message(c.toMaster.queue.removeFirst()))
                    }
                }
            }
        }

        step(500)
        assertTrue(master.cameras.all { it.link == LinkState.CONNECTED })
        val take = master.startAll(FrameRate.FPS_25, utcOffsetSeconds = 7_200)
        val at = requireNotNull(take.startAtCommonNanos)
        assertTrue(at >= source.now() + 300 * MS)
        assertEquals(2, take.commandIds.size)
        step(100)
        assertTrue(master.anyRecording)
        assertTrue(cameras.all { it.node.status.recording == RecordingState.SCHEDULED })
        step(600)
        val starts = cameras.map { it.actions.startedAt.getValue(take.recordingId) }
        // Both phones started at the same real instant (within one tick of the driver, 5 ms here), on a frame boundary.
        assertTrue("starts $starts", kotlin.math.abs(starts[0] - starts[1]) <= 5 * MS)
        assertEquals(cameras[0].node.status.recordingStartCommonNanos, cameras[1].node.status.recordingStartCommonNanos)
        val label = Timecode.ofTimeOfDay(at, 7_200, FrameRate.FPS_25)
        assertEquals(listOf(2, 0, 37), listOf(label.hours, label.minutes, label.seconds))
        assertTrue(label != Timecode.ofTimeOfDay(at - 1_000, 7_200, FrameRate.FPS_25)) // first instant of its frame

        val stops = master.stopAll(take.recordingId)
        step(50)
        assertEquals(2, stops.size)
        assertTrue(cameras.all { it.node.status.recording == RecordingState.IDLE })
        master.removeCamera("a")
        assertEquals(listOf("b"), master.cameras.map { it.id })
    }

    @Test
    fun monitorSchedulesWithAClockAndStartsDirectlyWithout() {
        val clock = FakeClock()
        val listener = RecordingListener()
        val withClock = MonitorNode(clock, SourceClock(clock, 1_792_195_200_000 * MS), ControllerLink("cam", clock, listener), Ids("take"))
        val noClock = MonitorNode(clock, null, ControllerLink("cam", clock, RecordingListener()), Ids("take"))
        withClock.start()
        noClock.start()
        assertEquals("take-1", withClock.startRecording())
        assertEquals("take-1", noClock.startRecording())
        assertEquals(1, withClock.link.pendingCommands)
        withClock.selectLut(null)
        withClock.setFocus(CommandBody.SetFocus(auto = true))
        assertEquals(3, withClock.link.pendingCommands)
        withClock.stop()
        assertEquals(LinkState.STOPPED, withClock.link.state)
        assertEquals(3, listener.results.size) // given up when stopped
        assertNull(withClock.link.status)
    }
}
