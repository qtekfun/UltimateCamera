package com.qtekfun.ultimatecamera.feature.multicam.monitor

import android.os.SystemClock
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.control.Session
import com.qtekfun.ultimatecamera.core.net.monitor.SignallingMessage
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.role.CommandResult
import com.qtekfun.ultimatecamera.core.net.role.ControllerDriver
import com.qtekfun.ultimatecamera.core.net.role.ControllerLink
import com.qtekfun.ultimatecamera.core.net.role.ControllerListener
import com.qtekfun.ultimatecamera.core.net.role.ControllerSignalling
import com.qtekfun.ultimatecamera.core.net.role.LinkState
import com.qtekfun.ultimatecamera.core.net.role.VideoRoute
import com.qtekfun.ultimatecamera.core.net.role.endpointDialer
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A paired camera this phone controls (master or monitor role). Built from the paired-device list: [pairKey] is the
 * long-term key, [endpoints] the dial order (discovered addresses first, then the stored ones; see `PeerAddressBook`).
 */
class RemoteCameraTarget(val id: String, val name: String, val pairKey: ByteArray, val endpoints: () -> List<Endpoint>) {
    /** The address the WebRTC monitor video of an older camera (legacy signalling socket) is fetched from: the first endpoint's host. */
    fun videoHost(): String? = endpoints().firstOrNull()?.host

    override fun toString(): String = "RemoteCameraTarget($id, $name, key=<hidden>)"
}

/**
 * Runtime of the control link to one camera (REQ-NET-003/007): a [ControllerLink] run by a [ControllerDriver] on one
 * confined dispatcher, exposed as a [StateFlow] of [RemoteSnapshot] for the UI. REC and STOP are optimistic: the button
 * moves at once ([RecIntent]) and goes back if the camera refuses or the command times out.
 */
class RemoteCamera(
    private val target: RemoteCameraTarget,
    private val local: LocalIdentity,
    private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtimeNanos() },
    /** Every control session opened with the camera (its clock key goes to the [SessionClockSource]). */
    private val onSession: (Session) -> Unit = {}
) : VideoRouter {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val confined: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _snapshot = MutableStateFlow(RemoteSnapshot(target.id, target.name))
    val snapshot: StateFlow<RemoteSnapshot> = _snapshot.asStateFlow()

    /** Local time (ms) this controller first saw the current take: the take time's base without a common clock. */
    @Volatile var recordingSeenSinceMs: Long? = null
        private set

    val id: String get() = target.id

    /** The camera's "Proxies" list, downloading into [dir] (REQ-NET-006, D59). */
    fun proxyDownloads(dir: java.io.File): ProxyDownloads = ProxyDownloads(target, local, dir)

    /** Monitor video signalling inside the authenticated control link (D70); the legacy socket only for older cameras. */
    override suspend fun videoRoute(): VideoRoute = withContext(confined) { signalling.route(legacyHost = target.videoHost()) }

    private val listener: ControllerListener = object : ControllerListener {
        override fun onSignal(message: SignallingMessage) = signalling.onSignal(message)

        override fun onLinkState(state: LinkState) {
            signalling.onLinkState(state)
            _snapshot.update { it.copy(link = state, everConnected = it.everConnected || state == LinkState.CONNECTED, rttNanos = link.rttNanos) }
        }

        override fun onStatus(status: CameraStatus) {
            trackTakeStart(status)
            _snapshot.update { s ->
                val intent = s.recIntent?.takeUnless { RemoteCameraMapper.intentReached(status, it) }
                s.copy(status = status, recIntent = intent, rttNanos = link.rttNanos)
            }
        }

        override fun onCommandResult(result: CommandResult) {
            val failed = result.outcome as? CommandResult.Outcome.Failed
            _snapshot.update { s ->
                val intent = s.recIntent?.takeUnless { it.commandId == result.commandId && (failed != null || RemoteCameraMapper.intentReached(s.status, it)) }
                s.copy(recIntent = intent, lastError = failed?.code ?: s.lastError)
            }
        }
    }

    private val link: ControllerLink = ControllerLink(target.id, clock, listener)
    private val signalling = ControllerSignalling(link) { block -> signallingScope.launch(confined) { block() } }

    /** Where the video session's sends hop to the link's thread; lives as long as this camera object. */
    private val signallingScope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        val dial = endpointDialer(target.endpoints) { ClientHandshake(local, HandshakeMode.RESUME, target.id, target.pairKey) }
        job = ControllerDriver(link, dial, onSession).launchIn(scope, confined)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Sends [body]; the result (or the translated refusal) comes back through [snapshot]. */
    fun send(scope: CoroutineScope, body: CommandBody) {
        scope.launch(confined) {
            _snapshot.update { it.copy(lastError = null) }
            val id = link.submit(body)
            when (body) {
                is CommandBody.StartRecording -> _snapshot.update { it.copy(recIntent = RecIntent(start = true, commandId = id)) }
                is CommandBody.StopRecording -> _snapshot.update { it.copy(recIntent = RecIntent(start = false, commandId = id)) }
                else -> Unit
            }
        }
    }

    /** REC or STOP depending on what the button shows now. [startAtCommonNanos] schedules a start (REQ-NET-005). */
    fun toggleRecording(scope: CoroutineScope, startAtCommonNanos: Long? = null, recordingId: String = UUID.randomUUID().toString()) {
        val ui = RemoteCameraMapper.recUi(_snapshot.value.status?.recording, _snapshot.value.recIntent)
        if (ui.showsRecording) {
            send(scope, CommandBody.StopRecording(_snapshot.value.status?.recordingId))
        } else {
            send(scope, CommandBody.StartRecording(recordingId, startAtCommonNanos))
        }
    }

    fun clearError() = _snapshot.update { it.copy(lastError = null) }

    private fun trackTakeStart(status: CameraStatus) {
        recordingSeenSinceMs = when {
            status.recording != RecordingState.RECORDING -> null
            recordingSeenSinceMs == null || _snapshot.value.status?.recordingId != status.recordingId -> SystemClock.elapsedRealtime()
            else -> recordingSeenSinceMs
        }
    }
}
