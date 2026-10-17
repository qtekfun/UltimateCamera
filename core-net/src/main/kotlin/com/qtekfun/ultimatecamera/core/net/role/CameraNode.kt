package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.CommonClockView
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.clock.ScheduledStart
import com.qtekfun.ultimatecamera.core.net.control.AckDetail
import com.qtekfun.ultimatecamera.core.net.control.ByeReason
import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.CaptureModeWire
import com.qtekfun.ultimatecamera.core.net.control.CaptureSettings
import com.qtekfun.ultimatecamera.core.net.control.ClipInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.HealthDetail
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.control.StatusWarning
import com.qtekfun.ultimatecamera.core.net.control.ThermalLevel

/** Outcome of a camera action. */
sealed interface ActionResult {
    data object Ok : ActionResult

    data class Failed(val code: ErrorCode, val detail: String? = null) : ActionResult
}

/**
 * What the camera app does for the control logic. Called on the driver's thread; must not block for long (start the
 * recorder asynchronously and report failures through [CameraNode.onLocalRecordingFailed]).
 */
interface CameraActions {
    fun startRecording(recordingId: String): ActionResult

    fun stopRecording(recordingId: String): ActionResult

    /** Exposure, focus, white balance, profile or LUT ([CommandBody] other than start/stop/status). */
    fun applySetting(command: CommandBody): ActionResult
}

/** Device health reported by the camera app (battery, PowerManager thermal status, storage, audio source). */
data class DeviceHealth(
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val thermal: ThermalLevel = ThermalLevel.NONE,
    val freeSpaceMinutes: Int? = null,
    val audioSource: String? = null,
    /** Indicators and predictive alerts (D52); null leaves the status without them. */
    val detail: HealthDetail? = null
)

/** The camera screen's own state for [CameraNode.updateLocal]; mirrors the optional status fields of D56. */
data class LocalCameraState(
    val profileId: String? = null,
    val profiles: List<String> = emptyList(),
    val lutId: String? = null,
    val monitorLook: String? = null,
    val capture: CaptureSettings = CaptureSettings(),
    val captureMode: CaptureModeWire? = null,
    val slaveMode: Boolean = false,
    val elapsedMs: Long? = null,
    val timecode: String? = null
)

/** Thresholds of the slave warnings (REQ-NET-008). */
data class WarningThresholds(val batteryLowPercent: Int = 15, val storageLowMinutes: Int = 10, val thermal: ThermalLevel = ThermalLevel.SEVERE) {
    fun evaluate(status: CameraStatus, clockRequired: Boolean): Set<StatusWarning> = buildSet {
        val battery = status.batteryPercent
        if (battery != null && battery <= batteryLowPercent && !status.charging) add(StatusWarning.BATTERY_LOW)
        if (status.thermal >= thermal) add(StatusWarning.THERMAL)
        val free = status.freeSpaceMinutes
        if (free != null && free <= storageLowMinutes) add(StatusWarning.STORAGE_LOW)
        if (clockRequired && !status.clockSynced) add(StatusWarning.CLOCK_NOT_SYNCED)
        if (status.recording == RecordingState.ERROR) add(StatusWarning.RECORDING_ERROR)
    }
}

/**
 * Camera side of the control protocol: pure logic for any number of controllers (master and monitors), driven by the
 * server driver (see `CameraDriver`); tests use fake sinks, actions and clocks.
 *
 * Invariants:
 * - The link never changes recording (REQ-NET-007): connections dropping, timing out or reconnecting only add or remove
 *   peers. Only a command, the local UI or a recorder failure starts or stops a take.
 * - Commands are idempotent: a repeated command id gets the same answer again without executing; a start for a take id
 *   already started (or finished) is a no-op, so replays after a reconnection and stale reordered commands are safe.
 * - Every controller gets the full status on connect and on every change, plus a periodic refresh.
 */
class CameraNode(
    private val bootId: String,
    private val clock: MonotonicClock,
    private val commonClock: CommonClockView,
    private val actions: CameraActions,
    private val timing: LinkTiming = LinkTiming(),
    private val thresholds: WarningThresholds = WarningThresholds(),
    /** Whether a clock source is expected (multicamera with master); then an unsynced clock is a warning. */
    private val clockRequired: Boolean = false,
    /** Called when the peer the camera should sync its clock with changes (null: none connected). */
    private val onClockSource: (peerId: String?) -> Unit = {}
) {
    private class Peer(val id: String, val role: DeviceRole, val sink: MessageSink, now: Long) {
        val heartbeat = HeartbeatState().also { it.lastPeerReceivedAt = now }
        var lastSentAt = now
        var lastStatusAt = now
    }

    private val peers = LinkedHashMap<String, Peer>()
    private val replies = Lru<String, ControlMessage>(REPLY_CACHE)
    private val knownTakes = Lru<String, Unit>(KNOWN_TAKES)
    private val recentClips = ArrayDeque<ClipInfo>()
    private var scheduledStartLocal: Long? = null
    private var scheduledStopLocal: Long? = null
    private var clockSource: String? = null

    var status: CameraStatus = CameraStatus(bootId = bootId, version = 0)
        private set

    val connectedPeers: Set<String> get() = peers.keys

    // --- Links -------------------------------------------------------------------------------------------------------

    /** An authenticated controller connected. A second connection of the same peer replaces the first. */
    fun onPeerConnected(peerId: String, role: DeviceRole, sink: MessageSink) {
        val now = clock.nanos()
        peers.remove(peerId)?.let {
            it.sink.send(ControlMessage.Bye(ByeReason.REPLACED))
            it.sink.close()
        }
        if (!role.isController) {
            sink.close()
            return
        }
        val peer = Peer(peerId, role, sink, now)
        peers[peerId] = peer
        refreshClockSource()
        send(peer, ControlMessage.Status(status))
        for (clip in recentClips) send(peer, ControlMessage.RecordingFinished(clip))
    }

    /** The connection of [peerId] ended. Recording is not touched. */
    fun onPeerDisconnected(peerId: String, sink: MessageSink) {
        val peer = peers[peerId] ?: return
        if (peer.sink !== sink) return // an older, replaced connection
        peers.remove(peerId)
        refreshClockSource()
    }

    /** Removes a peer that was unpaired on this phone: it is told not to redial. */
    fun unpair(peerId: String) {
        val peer = peers.remove(peerId) ?: return
        peer.sink.send(ControlMessage.Bye(ByeReason.UNPAIRED))
        peer.sink.close()
        refreshClockSource()
    }

    fun onMessage(peerId: String, decoded: Decoded) {
        val peer = peers[peerId] ?: return
        val now = clock.nanos()
        peer.heartbeat.lastPeerReceivedAt = now
        when (decoded) {
            is Decoded.Unknown -> send(peer, ControlMessage.Error(null, ErrorCode.UNKNOWN_MESSAGE, decoded.type))
            is Decoded.Message -> when (val message = decoded.message) {
                is ControlMessage.Heartbeat -> peer.heartbeat.onHeartbeat(message, now)
                is ControlMessage.Command -> onCommand(peer, message)
                is ControlMessage.Bye -> Unit // the connection closes next
                is ControlMessage.Status, is ControlMessage.Ack, is ControlMessage.Error, is ControlMessage.RecordingFinished -> Unit
            }
        }
    }

    /** Scheduled starts/stops, heartbeats, link timeouts and the periodic status. Call every 10–50 ms while scheduled. */
    fun tick() {
        val now = clock.nanos()
        scheduledStartLocal?.let { if (now >= it) beginScheduled() }
        scheduledStopLocal?.let { if (now >= it) stopNow(status.recordingId) }
        updateStatus { it } // picks up clock sync changes
        for (peer in peers.values.toList()) {
            when {
                now - peer.heartbeat.lastPeerReceivedAt > timing.linkTimeoutNanos -> {
                    peer.sink.close()
                    onPeerDisconnected(peer.id, peer.sink)
                }
                now - peer.lastStatusAt >= timing.statusIntervalNanos -> sendStatus(peer)
                now - peer.lastSentAt >= timing.heartbeatIntervalNanos -> send(peer, peer.heartbeat.outgoing(now))
            }
        }
    }

    /** Local monotonic time of the next scheduled action, so the driver can wake exactly then. */
    val nextDeadline: Long? get() = listOfNotNull(scheduledStartLocal, scheduledStopLocal).minOrNull()

    // --- Local events from the camera app --------------------------------------------------------------------------------

    fun updateHealth(health: DeviceHealth) = updateStatus {
        it.copy(
            batteryPercent = health.batteryPercent,
            charging = health.charging,
            thermal = health.thermal,
            freeSpaceMinutes = health.freeSpaceMinutes,
            audioSource = health.audioSource,
            health = health.detail
        )
    }

    /**
     * What the camera screen shows and uses now (profile, looks, live capture values, mode, take length). The app calls it
     * at most twice a second; every call that changes something is a new status for every controller.
     */
    fun updateLocal(local: LocalCameraState) = updateStatus {
        it.copy(
            profileId = local.profileId,
            profiles = local.profiles,
            lutId = local.lutId,
            monitorLook = local.monitorLook,
            capture = local.capture,
            captureMode = local.captureMode,
            slaveMode = local.slaveMode,
            elapsedMs = local.elapsedMs.takeIf { _ -> it.recording == RecordingState.RECORDING },
            timecode = local.timecode.takeIf { _ -> it.recording == RecordingState.RECORDING }
        )
    }

    /** Recording started from the camera's own UI. */
    fun onLocalRecordingStarted(recordingId: String) {
        knownTakes[recordingId] = Unit
        scheduledStartLocal = null
        updateStatus {
            it.copy(
                recording = RecordingState.RECORDING,
                recordingId = recordingId,
                scheduledStartCommonNanos = null,
                recordingStartCommonNanos = commonClock.toCommon(clock.nanos())
            )
        }
    }

    /** Recording stopped from the camera's own UI (or by the recorder, e.g. storage full). */
    fun onLocalRecordingStopped() {
        scheduledStopLocal = null
        updateStatus {
            it.copy(
                recording = RecordingState.IDLE,
                recordingId = null,
                recordingStartCommonNanos = null,
                scheduledStartCommonNanos = null,
                elapsedMs = null,
                timecode = null
            )
        }
    }

    fun onLocalRecordingFailed() {
        scheduledStartLocal = null
        scheduledStopLocal = null
        updateStatus { it.copy(recording = RecordingState.ERROR, scheduledStartCommonNanos = null) }
    }

    /** A take was closed on disk: every controller is told (now, and again on its next connection). */
    fun onRecordingFinished(clip: ClipInfo) {
        recentClips.addLast(clip)
        while (recentClips.size > RECENT_CLIPS) recentClips.removeFirst()
        broadcast(ControlMessage.RecordingFinished(clip))
    }

    // --- Commands --------------------------------------------------------------------------------------------------------

    private fun onCommand(peer: Peer, command: ControlMessage.Command) {
        replies[command.id]?.let { cached ->
            send(peer, if (cached is ControlMessage.Ack) cached.copy(duplicate = true) else cached)
            return
        }
        val reply = when (val body = command.body) {
            is CommandBody.StartRecording -> start(command.id, body)
            is CommandBody.StopRecording -> stop(command.id, body)
            is CommandBody.RequestStatus -> {
                sendStatus(peer)
                ack(command.id)
            }
            is CommandBody.SelectProfile, is CommandBody.SetCaptureMode ->
                if (isBusy()) error(command.id, ErrorCode.BUSY, "recording") else setting(command.id, body)
            else -> setting(command.id, body)
        }
        replies[command.id] = reply
        send(peer, reply)
    }

    @Suppress("ReturnCount")
    private fun start(id: String, body: CommandBody.StartRecording): ControlMessage {
        if (body.recordingId in knownTakes) {
            return ControlMessage.Ack(
                id,
                duplicate = true,
                detail = AckDetail(
                    atCommonNanos =
                        status.recordingStartCommonNanos ?: status.scheduledStartCommonNanos
                )
            )
        }
        if (isBusy()) return error(id, ErrorCode.ALREADY_RECORDING, status.recordingId)
        if ((status.freeSpaceMinutes ?: 1) <= 0) return error(id, ErrorCode.STORAGE_FULL, null)
        val now = clock.nanos()
        val at = body.startAtCommonNanos ?: return startNow(id, body.recordingId, lateNanos = null)
        return when (val local = ScheduledStart.toLocal(at, commonClock, now)) {
            ScheduledStart.Local.NotSynced -> error(id, ErrorCode.CLOCK_NOT_SYNCED, null)
            ScheduledStart.Local.TooFar -> error(id, ErrorCode.INVALID_ARGUMENT, "start too far ahead")
            is ScheduledStart.Local.Late -> startNow(id, body.recordingId, local.lateNanos)
            is ScheduledStart.Local.At -> {
                knownTakes[body.recordingId] = Unit
                scheduledStartLocal = local.localNanos
                updateStatus { it.copy(recording = RecordingState.SCHEDULED, recordingId = body.recordingId, scheduledStartCommonNanos = at) }
                ControlMessage.Ack(id, detail = AckDetail(atCommonNanos = at))
            }
        }
    }

    private fun startNow(id: String, recordingId: String, lateNanos: Long?): ControlMessage {
        knownTakes[recordingId] = Unit
        return when (val result = actions.startRecording(recordingId)) {
            is ActionResult.Failed -> {
                updateStatus { it.copy(recording = RecordingState.ERROR) }
                error(id, result.code, result.detail)
            }
            ActionResult.Ok -> {
                val startCommon = commonClock.toCommon(clock.nanos())
                updateStatus {
                    it.copy(
                        recording = RecordingState.RECORDING,
                        recordingId = recordingId,
                        recordingStartCommonNanos = startCommon,
                        scheduledStartCommonNanos = null
                    )
                }
                ControlMessage.Ack(id, detail = AckDetail(atCommonNanos = startCommon, lateMicros = lateNanos?.let { it / NANOS_PER_MICRO }))
            }
        }
    }

    private fun beginScheduled() {
        scheduledStartLocal = null
        val recordingId = status.recordingId ?: return
        when (actions.startRecording(recordingId)) {
            is ActionResult.Failed -> updateStatus { it.copy(recording = RecordingState.ERROR, scheduledStartCommonNanos = null) }
            ActionResult.Ok -> updateStatus {
                it.copy(recording = RecordingState.RECORDING, recordingStartCommonNanos = it.scheduledStartCommonNanos, scheduledStartCommonNanos = null)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun stop(id: String, body: CommandBody.StopRecording): ControlMessage {
        val current = status.recordingId.takeIf { isBusy() }
        val target = body.recordingId
        if (current == null || (target != null && target != current)) {
            // Nothing (or another take) is recording: stopping a take that already ended is a no-op.
            return if (target == null || target in knownTakes) ControlMessage.Ack(id, duplicate = true) else error(id, ErrorCode.NOT_RECORDING, target)
        }
        val at = body.stopAtCommonNanos
        if (at != null && status.recording == RecordingState.RECORDING) {
            val local = ScheduledStart.toLocal(at, commonClock, clock.nanos())
            if (local is ScheduledStart.Local.At) {
                scheduledStopLocal = local.localNanos
                return ControlMessage.Ack(id, detail = AckDetail(atCommonNanos = at))
            }
        }
        return when (val result = stopNow(current)) {
            is ActionResult.Failed -> error(id, result.code, result.detail)
            ActionResult.Ok -> ControlMessage.Ack(id, detail = AckDetail(atCommonNanos = commonClock.toCommon(clock.nanos())))
        }
    }

    private fun stopNow(recordingId: String?): ActionResult {
        scheduledStopLocal = null
        val wasScheduled = status.recording == RecordingState.SCHEDULED
        scheduledStartLocal = null
        val result = if (wasScheduled || recordingId == null) ActionResult.Ok else actions.stopRecording(recordingId)
        if (result == ActionResult.Ok) onLocalRecordingStopped()
        return result
    }

    private fun setting(id: String, body: CommandBody): ControlMessage {
        validate(body)?.let { return error(id, ErrorCode.INVALID_ARGUMENT, it) }
        return when (val result = actions.applySetting(body)) {
            is ActionResult.Failed -> error(id, result.code, result.detail)
            ActionResult.Ok -> {
                updateStatus { applyToStatus(it, body) }
                ack(id)
            }
        }
    }

    private fun applyToStatus(s: CameraStatus, body: CommandBody): CameraStatus = when (body) {
        is CommandBody.SetExposure -> s.copy(
            capture = s.capture.copy(
                autoExposure = body.auto,
                iso = body.iso,
                exposureTimeNanos = body.exposureTimeNanos,
                evCompensation = body.evCompensation ?: s.capture.evCompensation
            )
        )
        is CommandBody.SetFocus -> s.copy(capture = s.capture.copy(autoFocus = body.auto, focusDiopters = body.distanceDiopters))
        is CommandBody.SetWhiteBalance -> s.copy(capture = s.capture.copy(autoWhiteBalance = body.auto, whiteBalanceKelvin = body.kelvin))
        is CommandBody.SelectProfile -> s.copy(profileId = body.profileId)
        is CommandBody.SelectLut -> s.copy(lutId = body.lutId)
        is CommandBody.SetMonitorLook -> s.copy(monitorLook = body.token)
        is CommandBody.SetLocks -> s.copy(capture = s.capture.copy(aeLock = body.aeLock ?: s.capture.aeLock, awbLock = body.awbLock ?: s.capture.awbLock))
        is CommandBody.SetCaptureMode -> s.copy(captureMode = body.mode)
        is CommandBody.SetSlaveMode -> s.copy(slaveMode = body.enabled)
        else -> s
    }

    private fun isBusy() = status.recording == RecordingState.RECORDING || status.recording == RecordingState.SCHEDULED

    // --- Status ----------------------------------------------------------------------------------------------------------

    private fun updateStatus(change: (CameraStatus) -> CameraStatus) {
        val synced = commonClock.isSynced
        val changed = change(status).copy(
            clockSynced = synced,
            clockErrorMicros = commonClock.errorBoundNanos?.let { it / NANOS_PER_MICRO }
        )
        val withWarnings = changed.copy(warnings = thresholds.evaluate(changed, clockRequired))
        val onlyClockErrorMoved = withWarnings.copy(clockErrorMicros = status.clockErrorMicros) == status
        if (onlyClockErrorMoved && !significantClockChange(withWarnings)) return
        status = withWarnings.copy(version = status.version + 1)
        for (peer in peers.values.toList()) sendStatus(peer)
    }

    /** The clock error bound alone is only worth a status when it changes by more than a millisecond. */
    private fun significantClockChange(new: CameraStatus): Boolean {
        val old = status.clockErrorMicros
        val now = new.clockErrorMicros
        return (old == null) != (now == null) || (old != null && now != null && kotlin.math.abs(old - now) > CLOCK_REPORT_STEP_MICROS)
    }

    private fun sendStatus(peer: Peer) {
        peer.lastStatusAt = clock.nanos()
        send(peer, ControlMessage.Status(status))
    }

    private fun broadcast(message: ControlMessage) {
        for (peer in peers.values.toList()) send(peer, message)
    }

    private fun send(peer: Peer, message: ControlMessage) {
        peer.lastSentAt = clock.nanos()
        if (!peer.sink.send(message)) {
            peer.sink.close()
            onPeerDisconnected(peer.id, peer.sink)
        }
    }

    private fun refreshClockSource() {
        // The master is the clock source; without one, a monitor (camera + monitor pair).
        val source = peers.values.firstOrNull { it.role == DeviceRole.MASTER }?.id ?: peers.values.firstOrNull { it.role == DeviceRole.MONITOR }?.id
        if (source != clockSource) {
            clockSource = source
            onClockSource(source)
        }
    }

    private fun ack(id: String) = ControlMessage.Ack(id)

    private fun error(id: String, code: ErrorCode, detail: String?) = ControlMessage.Error(id, code, detail)

    private companion object {
        const val REPLY_CACHE = 256
        const val KNOWN_TAKES = 256
        const val RECENT_CLIPS = 16
        const val NANOS_PER_MICRO = 1_000L
        const val CLOCK_REPORT_STEP_MICROS = 1_000L

        @Suppress("CyclomaticComplexMethod")
        fun validate(body: CommandBody): String? = when (body) {
            is CommandBody.SetExposure -> when {
                body.iso != null && body.iso !in 1..MAX_ISO -> "iso"
                body.exposureTimeNanos != null && body.exposureTimeNanos <= 0 -> "exposure time"
                !body.auto && (body.iso == null || body.exposureTimeNanos == null) -> "manual exposure needs iso and time"
                else -> null
            }
            is CommandBody.SetFocus -> if (!body.auto && (body.distanceDiopters == null || body.distanceDiopters < 0f)) "focus distance" else null
            is CommandBody.SetWhiteBalance -> if (!body.auto && (body.kelvin == null || body.kelvin !in MIN_KELVIN..MAX_KELVIN)) "kelvin" else null
            is CommandBody.SelectProfile -> if (body.profileId.isBlank()) "profile" else null
            is CommandBody.SetLocks -> if (body.aeLock == null && body.awbLock == null) "locks" else null
            else -> null
        }

        const val MAX_ISO = 409_600
        const val MIN_KELVIN = 1_000
        const val MAX_KELVIN = 20_000
    }
}
