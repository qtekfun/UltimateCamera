package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.control.AckDetail
import com.qtekfun.ultimatecamera.core.net.control.ByeReason
import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.ClipInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import java.util.UUID

/** Connection state of a controller's link to one camera, for the UI. */
enum class LinkState {
    /** Not started, or stopped (also after the camera unpaired this phone). */
    STOPPED,

    /** Disconnected, waiting for the next dial (backoff). */
    WAITING,

    /** A dial and handshake is in progress. */
    CONNECTING,

    CONNECTED
}

/** Final result of a command, reported once per command id. */
data class CommandResult(val commandId: String, val body: CommandBody, val outcome: Outcome) {
    sealed interface Outcome {
        data class Ok(val duplicate: Boolean, val detail: AckDetail?) : Outcome

        data class Failed(val code: ErrorCode, val detail: String?) : Outcome
    }
}

/** Callbacks of a [ControllerLink], called synchronously from its methods (on the driver's thread). */
interface ControllerListener {
    fun onLinkState(state: LinkState) {}

    fun onStatus(status: CameraStatus) {}

    fun onCommandResult(result: CommandResult) {}

    fun onRecordingFinished(clip: ClipInfo) {}
}

/**
 * Controller side (master or monitor) of the control link to one camera: pure logic, driven by a driver that dials,
 * reads the connection and calls [tick] regularly (see `ControllerDriver`); tests drive it with a fake sink and clock.
 *
 * - Reconnects forever with [Backoff] after a drop or a failed dial (REQ-NET-007), until [stop] or a `Bye(UNPAIRED)`.
 * - Commands get a unique id and wait in order until acknowledged. A command whose connection broke before the ack is
 *   sent again after reconnecting; the camera does not execute an id twice, so the result is the same as one delivery.
 *   Unacknowledged commands older than [LinkTiming.commandLifetimeNanos] are given up as [ErrorCode.TIMED_OUT].
 * - The camera's state is resynced on each connection (it sends its full status first); late, duplicated or
 *   out-of-order statuses never overwrite a newer one ([CameraStatus.isSupersededBy]).
 * - Heartbeats both ways; nothing received for [LinkTiming.linkTimeoutNanos] closes the link and redials.
 */
class ControllerLink(
    val cameraId: String,
    private val clock: MonotonicClock,
    private val listener: ControllerListener,
    private val timing: LinkTiming = LinkTiming(),
    private val backoff: Backoff = Backoff(),
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private class Pending(val body: CommandBody, val submittedAt: Long)

    private val pending = LinkedHashMap<String, Pending>()
    private val seenClips = Lru<String, Unit>(SEEN_CLIPS)
    private var sink: MessageSink? = null
    private var heartbeat = HeartbeatState()
    private var lastSentAt = 0L
    private var nextDialAt = 0L
    private var attempt = 0

    var state: LinkState = LinkState.STOPPED
        private set

    /** Newest known camera status (kept while disconnected, for "last seen" display). */
    var status: CameraStatus? = null
        private set

    /** Control round trip measured by heartbeats on the current connection. */
    val rttNanos: Long? get() = heartbeat.rttNanos

    val pendingCommands: Int get() = pending.size

    fun start() {
        if (state != LinkState.STOPPED) return
        attempt = 0
        nextDialAt = clock.nanos()
        setState(LinkState.WAITING)
    }

    /** Closes the link and gives up pending commands. */
    fun stop() {
        closeSink(ControlMessage.Bye(ByeReason.SHUTDOWN))
        failAllPending(ErrorCode.TIMED_OUT, "link stopped")
        setState(LinkState.STOPPED)
    }

    /** True once when it is time to dial: the state becomes CONNECTING and the driver must call back. */
    fun pollDial(): Boolean {
        if (state != LinkState.WAITING || clock.nanos() < nextDialAt) return false
        setState(LinkState.CONNECTING)
        return true
    }

    /** The dial and handshake succeeded. Sends every pending command, in submission order. */
    fun onConnected(newSink: MessageSink) {
        if (state != LinkState.CONNECTING) {
            newSink.close()
            return
        }
        sink = newSink
        heartbeat = HeartbeatState().also { it.lastPeerReceivedAt = clock.nanos() }
        attempt = 0
        setState(LinkState.CONNECTED)
        for ((id, p) in pending.entries.toList()) if (!send(ControlMessage.Command(id, p.body))) return
    }

    /** The dial or handshake failed; [permanent] (e.g. this phone is no longer paired) stops redialling. */
    fun onDialFailed(permanent: Boolean = false) {
        if (state != LinkState.CONNECTING) return
        if (permanent) {
            failAllPending(ErrorCode.TIMED_OUT, "not paired")
            setState(LinkState.STOPPED)
        } else {
            scheduleRedial()
        }
    }

    /** The connection broke (read error, end of stream). [of] ignores the report of an older, already replaced sink. */
    fun onDisconnected(of: MessageSink? = null) {
        if (state != LinkState.CONNECTED || (of != null && of !== sink)) return
        sink?.close()
        sink = null
        scheduleRedial()
    }

    fun onMessage(decoded: Decoded) {
        if (state != LinkState.CONNECTED) return
        val now = clock.nanos()
        heartbeat.lastPeerReceivedAt = now
        val message = (decoded as? Decoded.Message)?.message ?: return
        when (message) {
            is ControlMessage.Heartbeat -> heartbeat.onHeartbeat(message, now)
            is ControlMessage.Status -> acceptStatus(message.status)
            is ControlMessage.Ack -> resolve(message.commandId, CommandResult.Outcome.Ok(message.duplicate, message.detail))
            is ControlMessage.Error -> message.commandId?.let { resolve(it, CommandResult.Outcome.Failed(message.code, message.detail)) }
            is ControlMessage.RecordingFinished -> if (seenClips.put(message.clip.recordingId, Unit) == null) listener.onRecordingFinished(message.clip)
            is ControlMessage.Bye -> onBye(message.reason)
            is ControlMessage.Command -> send(ControlMessage.Error(message.id, ErrorCode.UNSUPPORTED, "controllers take no commands"))
        }
    }

    /** Heartbeats, link timeout and command expiry. Call every 100–250 ms. */
    fun tick() {
        val now = clock.nanos()
        expireCommands(now)
        if (state != LinkState.CONNECTED) return
        if (now - heartbeat.lastPeerReceivedAt > timing.linkTimeoutNanos) {
            onDisconnected()
            return
        }
        if (now - lastSentAt >= timing.heartbeatIntervalNanos) send(heartbeat.outgoing(now))
    }

    /** Queues [body] and sends it now if connected. Returns the command id; the result arrives via the listener. */
    fun submit(body: CommandBody): String {
        val id = newId()
        pending[id] = Pending(body, clock.nanos())
        if (state == LinkState.CONNECTED) send(ControlMessage.Command(id, body))
        return id
    }

    private fun acceptStatus(new: CameraStatus) {
        val current = status
        if (current == null || current.isSupersededBy(new)) {
            status = new
            listener.onStatus(new)
        }
    }

    private fun resolve(id: String, outcome: CommandResult.Outcome) {
        val p = pending.remove(id) ?: return // duplicate or late answer
        listener.onCommandResult(CommandResult(id, p.body, outcome))
    }

    private fun onBye(reason: ByeReason) {
        if (reason == ByeReason.UNPAIRED) {
            closeSink(null)
            failAllPending(ErrorCode.TIMED_OUT, "unpaired by the camera")
            setState(LinkState.STOPPED)
        } else {
            onDisconnected()
        }
    }

    private fun expireCommands(now: Long) {
        val expired = pending.filterValues { now - it.submittedAt > timing.commandLifetimeNanos }.keys
        for (id in expired) resolve(id, CommandResult.Outcome.Failed(ErrorCode.TIMED_OUT, "no answer from the camera"))
    }

    private fun failAllPending(code: ErrorCode, detail: String) {
        for (id in pending.keys.toList()) resolve(id, CommandResult.Outcome.Failed(code, detail))
    }

    private fun send(message: ControlMessage): Boolean {
        val s = sink ?: return false
        lastSentAt = clock.nanos()
        if (!s.send(message)) {
            onDisconnected()
            return false
        }
        return true
    }

    private fun closeSink(bye: ControlMessage.Bye?) {
        val s = sink ?: return
        if (bye != null) s.send(bye)
        s.close()
        sink = null
    }

    private fun scheduleRedial() {
        nextDialAt = clock.nanos() + backoff.delayNanos(attempt++)
        setState(LinkState.WAITING)
    }

    private fun setState(new: LinkState) {
        if (state == new) return
        state = new
        listener.onLinkState(new)
    }

    private companion object {
        const val SEEN_CLIPS = 64
    }
}
