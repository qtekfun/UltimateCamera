package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.CommonClockView
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.ClipInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import kotlin.random.Random

const val MS = 1_000_000L

class FakeClock(var now: Long = 1_000 * MS) : MonotonicClock {
    override fun nanos(): Long = now

    fun advance(ms: Long) {
        now += ms * MS
    }
}

/** A camera's view of the common clock: common = local + [offset], error bound [bound]; null offset = not synced. */
class FakeCommonClock(var offset: Long? = 0L, var bound: Long = 500_000L) : CommonClockView {
    override fun toCommon(local: Long): Long? = offset?.let { local + it }

    override fun toLocal(common: Long): Long? = offset?.let { common - it }

    override val errorBoundNanos: Long? get() = offset?.let { bound }
}

/** One direction of a connection: messages wait in [queue] until the test delivers them (or loses them). */
class FakeSink : MessageSink {
    val queue = ArrayDeque<ControlMessage>()
    var broken = false
    var closed = false

    override fun send(message: ControlMessage): Boolean {
        if (broken || closed) return false
        // Through the real codec, so every message the machines exchange is also a wire-format test.
        val decoded = ControlProtocol.decode(ControlProtocol.encode(message)) as Decoded.Message
        queue.addLast(decoded.message)
        return true
    }

    override fun close() {
        closed = true
    }
}

class FakeActions : CameraActions {
    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()
    val settings = mutableListOf<CommandBody>()
    var failStart: ErrorCode? = null

    override fun startRecording(recordingId: String): ActionResult {
        failStart?.let { return ActionResult.Failed(it, "test") }
        started += recordingId
        return ActionResult.Ok
    }

    override fun stopRecording(recordingId: String): ActionResult {
        stopped += recordingId
        return ActionResult.Ok
    }

    override fun applySetting(command: CommandBody): ActionResult {
        settings += command
        return ActionResult.Ok
    }
}

class RecordingListener : ControllerListener {
    val states = mutableListOf<LinkState>()
    val statuses = mutableListOf<CameraStatus>()
    val results = mutableListOf<CommandResult>()
    val clips = mutableListOf<ClipInfo>()

    override fun onLinkState(state: LinkState) {
        states += state
    }

    override fun onStatus(status: CameraStatus) {
        statuses += status
    }

    override fun onCommandResult(result: CommandResult) {
        results += result
    }

    override fun onRecordingFinished(clip: ClipInfo) {
        clips += clip
    }

    fun result(id: String) = results.single { it.commandId == id }
}

/** A deterministic id source: "id-1", "id-2", … */
class Ids(private val prefix: String = "id") : () -> String {
    private var n = 0

    override fun invoke(): String = "$prefix-${++n}"
}

/**
 * One camera and one controller wired through fake sinks. The test decides when messages are delivered, lost,
 * duplicated or reordered, and when the link drops.
 */
class Wire(
    val clock: FakeClock = FakeClock(),
    val cameraClock: FakeCommonClock = FakeCommonClock(),
    val role: DeviceRole = DeviceRole.MONITOR,
    val timing: LinkTiming = LinkTiming()
) {
    val actions = FakeActions()
    val events = RecordingListener()
    val camera = CameraNode("boot-1", clock, cameraClock, actions, timing)
    val link = ControllerLink(CAMERA_ID, clock, events, timing, Backoff(random = Random(7)), Ids("cmd"))
    var toCamera = FakeSink()
    var toController = FakeSink()

    init {
        link.start()
    }

    /** Dials if the link wants to, and connects both ends. Returns false when the link was not ready to dial. */
    fun connect(): Boolean {
        if (!link.pollDial()) return false
        toCamera = FakeSink()
        toController = FakeSink()
        camera.onPeerConnected(CONTROLLER_ID, role, toController)
        link.onConnected(toCamera)
        return true
    }

    /** Delivers everything queued, both ways, until quiet. */
    fun pump() {
        while (toCamera.queue.isNotEmpty() || toController.queue.isNotEmpty()) {
            while (toCamera.queue.isNotEmpty()) camera.onMessage(CONTROLLER_ID, Decoded.Message(toCamera.queue.removeFirst()))
            while (toController.queue.isNotEmpty()) link.onMessage(Decoded.Message(toController.queue.removeFirst()))
        }
    }

    /** The Wi-Fi goes away: both ends notice (read error). Queued messages are lost. */
    fun drop() {
        toCamera.broken = true
        toController.broken = true
        toCamera.queue.clear()
        toController.queue.clear()
        camera.onPeerDisconnected(CONTROLLER_ID, toController)
        link.onDisconnected(toCamera)
    }

    /** Advances time in [stepMs] steps, ticking both ends and redialling when the link asks. */
    fun run(ms: Long, stepMs: Long = 50, reconnect: Boolean = true) {
        var left = ms
        while (left > 0) {
            clock.advance(stepMs)
            left -= stepMs
            camera.tick()
            link.tick()
            if (reconnect) connect()
            pump()
        }
    }

    companion object {
        const val CAMERA_ID = "camera-id"
        const val CONTROLLER_ID = "controller-id"
    }
}
