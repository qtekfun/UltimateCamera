package com.qtekfun.ultimatecamera.feature.camera.remote

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.gl.StreamOutput
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.CameraClockBridge
import com.qtekfun.ultimatecamera.core.net.clock.ClockClient
import com.qtekfun.ultimatecamera.core.net.clock.ClockEstimator
import com.qtekfun.ultimatecamera.core.net.clock.ClockServer
import com.qtekfun.ultimatecamera.core.net.clock.FrameRate
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.clock.SyncedClock
import com.qtekfun.ultimatecamera.core.net.clock.Timecode
import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.control.Session
import com.qtekfun.ultimatecamera.core.net.discovery.NsdServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceAdvert
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorLinkState
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorSender
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorVideoConfig
import com.qtekfun.ultimatecamera.core.net.pairing.B64
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDeviceStore
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevices
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesFile
import com.qtekfun.ultimatecamera.core.net.role.ActionResult
import com.qtekfun.ultimatecamera.core.net.role.CameraActions
import com.qtekfun.ultimatecamera.core.net.role.CameraDriver
import com.qtekfun.ultimatecamera.core.net.role.CameraNode
import com.qtekfun.ultimatecamera.core.net.role.LocalCameraState
import com.qtekfun.ultimatecamera.core.net.role.PairingStore
import com.qtekfun.ultimatecamera.core.net.role.RoleSource
import com.qtekfun.ultimatecamera.core.net.role.RoleSources
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.feature.camera.CameraViewModel
import com.qtekfun.ultimatecamera.feature.camera.ProfileLadder
import com.qtekfun.ultimatecamera.feature.camera.toDeviceHealth
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the camera screen shows of the camera role: off, or on with [monitors] authenticated controllers connected. */
data class CameraRoleUi(val active: Boolean = false, val monitors: Int = 0, val streaming: Boolean = false)

/**
 * The camera role inside the camera screen (Phase 6, D56): when [RoleSources] says this phone is a camera and the screen
 * is started, it listens on the control port, advertises itself over mDNS, accepts authenticated monitors and masters,
 * applies their commands to the screen (REQ-NET-003), publishes the status at 2 Hz with the health snapshot (D52) and
 * sends the monitored image to a monitor over WebRTC. Links dropping never touch the take (REQ-NET-007); slave mode
 * (REQ-NET-008) only dims the screen.
 *
 * Every call into the [CameraNode] runs on one confined dispatcher, as the node requires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraRoleBinding(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: CameraController,
    private val viewModel: CameraViewModel,
    private val roleSource: () -> RoleSource = { RoleSources.current }
) {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val started = MutableStateFlow(false)
    private val _ui = MutableStateFlow(CameraRoleUi())
    private val _slave = MutableStateFlow(false)

    @Volatile private var node: CameraNode? = null

    @Volatile private var bridge: CameraClockBridge? = null

    val ui: StateFlow<CameraRoleUi> = _ui.asStateFlow()

    /** Slave mode (REQ-NET-008): dimmed screen, minimal UI, REC still visible; toggled here or from a monitor. */
    val slave: StateFlow<Boolean> = _slave.asStateFlow()

    init {
        scope.launch {
            combine(roleSource().role, started) { role, on -> role == DeviceRole.CAMERA && on }.collectLatest { on ->
                if (on) run() else _slave.value = false
            }
        }
    }

    fun start() {
        started.value = true
    }

    fun stop() {
        started.value = false
    }

    fun setSlave(enabled: Boolean) {
        _slave.value = enabled
    }

    @Suppress("TooGenericExceptionCaught") // Network boundary: the camera keeps working when the role cannot start.
    private suspend fun run() {
        try {
            coroutineScope { runRole(this) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "camera role stopped: ${e.message}")
        } finally {
            node = null
            ActiveCommonClock.current = null
            _ui.value = CameraRoleUi()
            withContext(NonCancellable) { controller.streamOutput = null }
        }
    }

    private suspend fun runRole(roleScope: CoroutineScope) {
        val source = roleSource()
        val pairings: PairingStore = source.pairings() ?: Pairings(File(context.filesDir, PairedDeviceStore.FILE_NAME))
        val ownId = withContext(Dispatchers.IO) { pairings.ownId }
        val name = source.deviceName() ?: Build.MODEL
        // elapsedRealtimeNanos: the clock of the clock client and of CameraClockBridge (D58).
        val clock = MonotonicClock { SystemClock.elapsedRealtimeNanos() }
        val estimator = ClockEstimator()
        val common = SyncedClock(estimator)
        bridge = CameraClockBridge(local = clock, camera = { controller.cameraClockNanos() ?: SystemClock.elapsedRealtimeNanos() })
        val remotes = HashMap<String, Pair<String, ByteArray>>()
        var clockJob: Job? = null
        val created = withContext(confined) {
            CameraNode(
                bootId = UUID.randomUUID().toString(),
                clock = clock,
                commonClock = common,
                actions = Actions(),
                onClockSource = source@{ peerId ->
                    clockJob?.cancel()
                    estimator.clear()
                    val (host, key) = peerId?.let { remotes[it] } ?: return@source
                    clockJob = roleScope.launch { ClockClient(host, ClockServer.DEFAULT_PORT, key, clock, estimator).run() }
                }
            )
        }
        node = created
        // The screen's time-of-day timecode and the takes follow the session clock while the role runs (D58).
        ActiveCommonClock.current = common
        _ui.value = CameraRoleUi(active = true)
        val keyring = object : Keyring {
            override fun pairKey(deviceId: String): ByteArray? = pairings.pairKey(deviceId)

            override fun pairingToken(): ByteArray? = source.pairingToken()

            override fun accepts(role: DeviceRole): Boolean = role.isController
        }
        CameraDriver(
            node = created,
            clock = clock,
            local = LocalIdentity(ownId, name, DeviceRole.CAMERA),
            keyring = keyring,
            port = ControlProtocol.DEFAULT_PORT,
            onPaired = { session -> pairings.store(session) },
            // Called on the confined dispatcher, before the node hears of the peer (and picks its clock source).
            onRemote = { session, remote -> remotes[session.peerId] = hostOf(remote) to session.clockKey }
        ).launchIn(roleScope, confined)
        val advertised = ServiceAdvert(ownId, name, DeviceRole.CAMERA, ControlProtocol.DEFAULT_PORT, ControlProtocol.VERSION)
        val advert = NsdServiceDiscovery(context).advertise(advertised)
        try {
            roleScope.launch(confined) { followRecording(created) }
            roleScope.launch(confined) { publishStatus(created) }
            roleScope.launch { streamVideo(created, remotes) }
            awaitCancellation()
        } finally {
            advert.close()
        }
    }

    /** Takes started or stopped on the camera itself (UI, volume keys, guard) reach the node; remote ones are already there. */
    private suspend fun followRecording(node: CameraNode) {
        var recording = controller.state.value is CameraState.Recording
        controller.state.collect { state ->
            val now = state is CameraState.Recording
            if (now == recording) return@collect
            recording = now
            val nodeRecording = node.status.recording == RecordingState.RECORDING || node.status.recording == RecordingState.SCHEDULED
            when {
                now && !nodeRecording -> node.onLocalRecordingStarted(UUID.randomUUID().toString())
                !now && nodeRecording -> node.onLocalRecordingStopped()
            }
        }
    }

    /** At most twice a second (REQ-NET-003): profile, looks, live controls, take length, timecode and health. */
    private suspend fun publishStatus(node: CameraNode) {
        while (currentCoroutineContext().isActive) {
            val ui = viewModel.state.value
            val looks = viewModel.looks.state.value
            val profile = ui.profile
            val start = node.status.recordingStartCommonNanos
            node.updateLocal(
                LocalCameraState(
                    profileId = profile?.let { ProfileKey.of(it).id },
                    lutId = looks.recording.token,
                    monitorLook = looks.monitor.token,
                    capture = RemoteCommands.captureSettings(controller.controls.value, controller.live.value, controller.ranges.value),
                    captureMode = RemoteCommands.wire(viewModel.photo.state.value.mode),
                    slaveMode = _slave.value,
                    elapsedMs = ui.elapsedMs,
                    droppedFrames = viewModel.status.value.droppedFrames.toLong().takeIf { ui.camera is CameraState.Recording },
                    timecode = if (start != null && profile != null) {
                        Timecode.ofTimeOfDay(start, TimeZone.getDefault().getOffset(start / NANOS_PER_MS) / MS_PER_S, FrameRate(profile.fps)).toString()
                    } else {
                        null
                    }
                )
            )
            node.updateHealth(viewModel.health.value.toDeviceHealth())
            // D57 lists for the monitor's pickers; unchanged lists are not re-sent (the status only changes when they do).
            node.updateChoices(profileChoices(ui.profiles, ui.codec), RemoteCommands.lutChoices(looks.library, looks.tenBit))
            _ui.value = _ui.value.copy(monitors = node.connectedPeers.size)
            delay(STATUS_INTERVAL_MS)
        }
    }

    /**
     * The monitored image to one monitor at a time over WebRTC (REQ-NET-004), only while an authenticated controller is
     * connected and only to the address of one: the signalling socket itself is not authenticated yet (D31).
     */
    private suspend fun streamVideo(node: CameraNode, remotes: Map<String, Pair<String, ByteArray>>) {
        while (currentCoroutineContext().isActive) {
            val profile = viewModel.state.value.profile
            if (profile != null && withContext(confined) { node.connectedPeers.isNotEmpty() }) {
                val (width, height) = StreamOutput.sizeFor(profile.width, profile.height)
                if (!streamOnce(node, remotes, width, height)) return
            }
            delay(VIDEO_RETRY_MS)
        }
    }

    /** One WebRTC session until it ends; false when libwebrtc cannot be set up on this phone (no video at all then). */
    @Suppress("TooGenericExceptionCaught") // libwebrtc may be missing or fail to set up: the camera works without video.
    private suspend fun streamOnce(node: CameraNode, remotes: Map<String, Pair<String, ByteArray>>, width: Int, height: Int): Boolean {
        val config = MonitorVideoConfig(width = width, height = height, fps = StreamOutput.MAX_FPS)
        val sender = try {
            withContext(Dispatchers.Default) { MonitorSender(context, config) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "no monitor video: ${e.message}")
            return false
        }
        try {
            if (!sender.start()) return true
            val allowed = withContext(confined) { node.connectedPeers.mapNotNull { remotes[it]?.first } }
            if (hostOf(sender.peer.orEmpty()) !in allowed) return true
            // Drawn only while a monitor is connected: no GPU work for an empty link.
            controller.streamOutput = StreamOutput(sender.inputSurface, width, height, 1)
            _ui.value = _ui.value.copy(streaming = true)
            sender.state.first { it == MonitorLinkState.FAILED || it == MonitorLinkState.CLOSED }
        } finally {
            _ui.value = _ui.value.copy(streaming = false)
            withContext(NonCancellable) {
                controller.streamOutput = null
                sender.close()
            }
        }
        return true
    }

    /** Camera actions for the node: called on [confined]; the work is handed to the view model and never blocks. */
    private inner class Actions : CameraActions {
        override fun startRecording(recordingId: String): ActionResult {
            if (viewModel.photo.active) return ActionResult.Failed(ErrorCode.BUSY, "photo mode")
            if (controller.state.value !is CameraState.Previewing) return ActionResult.Failed(ErrorCode.CAMERA_ERROR, "camera not ready")
            scope.launch {
                if (!viewModel.startFromRemote()) withContext(confined) { node?.onLocalRecordingFailed() }
            }
            return ActionResult.Ok
        }

        /** Synchronised start (D58): the recorder is armed now for the camera-clock instant of [startLocalNanos]. */
        override fun armRecording(recordingId: String, startLocalNanos: Long): ActionResult? {
            val cameraClock = bridge ?: return null
            if (viewModel.photo.active) return ActionResult.Failed(ErrorCode.BUSY, "photo mode")
            if (controller.state.value !is CameraState.Previewing) return ActionResult.Failed(ErrorCode.CAMERA_ERROR, "camera not ready")
            val at = cameraClock.toCamera(startLocalNanos)
            scope.launch {
                if (!viewModel.startFromRemote(at)) withContext(confined) { node?.onLocalRecordingFailed() }
            }
            return ActionResult.Ok
        }

        override fun stopRecording(recordingId: String): ActionResult {
            viewModel.stopFromRemote()
            return ActionResult.Ok
        }

        override fun applySetting(command: CommandBody): ActionResult =
            when (val action = RemoteCommands.map(command, controller.controls.value, controller.ranges.value)) {
                is RemoteAction.Invalid -> ActionResult.Failed(ErrorCode.INVALID_ARGUMENT, action.reason)
                is RemoteAction.Unsupported -> ActionResult.Failed(ErrorCode.UNSUPPORTED, action.reason)
                is RemoteAction.Profile -> profile(action.key)
                else -> {
                    scope.launch { apply(action) }
                    ActionResult.Ok
                }
            }

        private fun profile(key: ProfileKey): ActionResult {
            val ui = viewModel.state.value
            if (ui.camera is CameraState.Recording) return ActionResult.Failed(ErrorCode.BUSY, "recording")
            // The open ladder first (it may hold probed modes, D59), else the fixed ladder of the asked codec.
            val target = ui.profiles.firstOrNull(key::matches)
                ?: ProfileLadder.profiles(experimental = false, codec = key.codec).firstOrNull(key::matches)
                ?: return ActionResult.Failed(ErrorCode.UNSUPPORTED, key.id)
            scope.launch {
                if (ui.codec != key.codec) viewModel.selectCodec(key.codec).join()
                viewModel.selectProfile(target)
            }
            return ActionResult.Ok
        }

        private fun apply(action: RemoteAction) {
            when (action) {
                is RemoteAction.Controls -> controller.setControls(action.controls)
                is RemoteAction.Recording -> viewModel.looks.setRecording(action.look)
                is RemoteAction.Monitor -> viewModel.looks.setMonitor(action.look)
                is RemoteAction.Mode -> viewModel.setCaptureMode(action.mode)
                is RemoteAction.Slave -> _slave.value = action.enabled
                is RemoteAction.Profile, is RemoteAction.Invalid, is RemoteAction.Unsupported -> Unit
            }
        }
    }

    /**
     * Fallback when the [RoleSource] brings no store (tests, standalone builds): the plain paired devices file, loaded
     * once, read by the handshake, written when a monitor pairs.
     */
    private class Pairings(file: File) : PairingStore {
        private val store = PairedDeviceStore(file)

        @Volatile private var content: PairedDevicesFile? = null

        override val ownId: String by lazy {
            val loaded = store.load().file
            content = loaded
            loaded.ownId
        }

        override fun pairKey(deviceId: String): ByteArray? = content?.devices?.firstOrNull { it.id == deviceId }?.keyBytes()

        @Synchronized
        override fun store(session: Session) {
            val key = session.newPairKey ?: return
            val current = content ?: return
            val device = PairedDevice(session.peerId, session.peerName, session.peerRole, B64.encode(key), pairedAtEpochMs = System.currentTimeMillis())
            val updated = PairedDevices.upsert(current, device)
            content = updated
            try {
                store.save(updated)
            } catch (e: IOException) {
                Log.w(TAG, "pairing not saved: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pairing not saved: ${e.message}")
            }
        }
    }

    /**
     * The open ladder (probed modes included) plus the fixed ladder of every other codec, by protocol id; the camera falls
     * down the ladder if a mode is refused.
     */
    private fun profileChoices(open: List<RecordingProfile>, codec: VideoCodec): List<ChoiceInfo> {
        if (open !== choicesFor) {
            choicesFor = open
            val others = VideoCodec.entries.filter { it != codec }.flatMap { ProfileLadder.profiles(experimental = false, codec = it) }
            choices = (open + others).map(RemoteCommands::profileChoice)
        }
        return choices
    }

    /** The open ladder (by identity) the cached [choices] were built from; touched only by the status loop. */
    private var choicesFor: List<RecordingProfile>? = null
    private var choices: List<ChoiceInfo> = emptyList()

    private companion object {
        const val TAG = "CameraRole"
        const val STATUS_INTERVAL_MS = 500L
        const val VIDEO_RETRY_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000

        /** `host` of a `host:port` (IPv6 hosts may come in brackets or with a zone). */
        fun hostOf(remote: String): String = remote.substringBeforeLast(':').removePrefix("/").removePrefix("[").removeSuffix("]")
    }
}
