package com.qtekfun.ultimatecamera.feature.multicam.roles

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.SyncedStart as CoreSyncedStart
import com.qtekfun.ultimatecamera.core.net.control.ControlStrings
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.discovery.NsdServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.discovery.PeerAddressBook
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice
import com.qtekfun.ultimatecamera.core.net.pairing.PairingParseResult
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRoleRepository
import com.qtekfun.ultimatecamera.feature.multicam.R
import com.qtekfun.ultimatecamera.feature.multicam.monitor.RemoteCameraTarget
import com.qtekfun.ultimatecamera.feature.multicam.monitor.SyncedStart
import com.qtekfun.ultimatecamera.feature.multicam.pairing.DiscoveryStatus
import com.qtekfun.ultimatecamera.feature.multicam.pairing.DiscoveryWatcher
import com.qtekfun.ultimatecamera.feature.multicam.pairing.HostState
import com.qtekfun.ultimatecamera.feature.multicam.pairing.NearbyDevice
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairOutcome
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesRepository
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingClient
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingHost
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingWindow
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ShortCode
import com.qtekfun.ultimatecamera.feature.multicam.session.ChooseOutcome
import com.qtekfun.ultimatecamera.feature.multicam.session.MulticamSession
import com.qtekfun.ultimatecamera.feature.multicam.session.Relation
import com.qtekfun.ultimatecamera.feature.multicam.session.RoleChoice
import com.qtekfun.ultimatecamera.feature.multicam.session.SessionRoles
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of a pairing started on this phone (scan or typed code). */
sealed interface PairingProgress {
    data object Idle : PairingProgress

    data object Working : PairingProgress

    data class Done(val name: String, val deviceId: String) : PairingProgress

    data class Error(@param:StringRes val message: Int) : PairingProgress
}

/** Progress of a role choice made on this phone (D80). */
sealed interface RoleProgress {
    data object Idle : RoleProgress

    data object Working : RoleProgress

    data class Done(val own: MulticamRole?) : RoleProgress

    data class Error(@param:StringRes val message: Int) : RoleProgress
}

/**
 * Pairing (either phone shows or scans, D80), the roles chosen afterwards with a paired phone, paired devices and live
 * discovery for the multicamera screens (REQ-NET-002/003).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MulticamViewModel internal constructor(
    private val roles: MulticamRoleRepository,
    loadDevices: () -> PairedDevicesRepository,
    /** LAN discovery (NSD in the app); a new one for the pairing listener. */
    private val newDiscovery: () -> ServiceDiscovery,
    private val deviceName: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Roles after pairing; null leaves the role choice unavailable (tests of pairing alone). */
    private val session: MulticamSession? = null
) : ViewModel() {
    constructor(application: Application, roles: MulticamRoleRepository) : this(
        roles,
        { PairedDevicesRepository.getInstance(application) },
        { NsdServiceDiscovery(application) },
        MulticamRoleSource.deviceName(application),
        session = MulticamSession.getInstance(application, roles)
    )

    private val repository = viewModelScope.async(io) { loadDevices() }

    @Volatile private var addressBook: PeerAddressBook? = null

    @Volatile private var loaded: PairedDevicesRepository? = null
    private val watcher = DiscoveryWatcher(newDiscovery()) { event -> addressBook?.onEvent(event) }
    private var host: PairingHost? = null
    private var ticker: Job? = null

    val role: StateFlow<MulticamRole?> = roles.multicamRole.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val devices: StateFlow<List<PairedDevice>> = flow { emit(repository.await()) }
        .flatMapLatest { it.devices }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val discovery: StateFlow<DiscoveryStatus> = watcher.status

    private val _ownId = MutableStateFlow("")
    val ownId: StateFlow<String> = _ownId.asStateFlow()

    private val _keysProtected = MutableStateFlow(true)
    val keysProtected: StateFlow<Boolean> = _keysProtected.asStateFlow()

    private val _hostState = MutableStateFlow<StateFlow<HostState>>(MutableStateFlow(HostState.Idle))
    val hostState: StateFlow<HostState> = _hostState.flatMapLatest { it }.stateIn(viewModelScope, SharingStarted.Eagerly, HostState.Idle)

    private val _progress = MutableStateFlow<PairingProgress>(PairingProgress.Idle)
    val progress: StateFlow<PairingProgress> = _progress.asStateFlow()

    private val _roleProgress = MutableStateFlow<RoleProgress>(RoleProgress.Idle)
    val roleProgress: StateFlow<RoleProgress> = _roleProgress.asStateFlow()

    /** The other phone made this one its monitor: the screen opens the control page once. */
    val openControl: StateFlow<Boolean> = session?.openControl ?: MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val repo = repository.await()
            loaded = repo
            addressBook = PeerAddressBook(repo.ownId, repo::isPaired)
            _ownId.value = repo.ownId
            _keysProtected.value = repo.keysProtected
            watcher.status.collect { status -> touchSeen(repo, status) }
        }
    }

    /** Live discovery only while a multicamera screen is shown (battery). */
    fun startDiscovery() = watcher.start()

    fun stopDiscovery() = watcher.stop()

    /**
     * The paired cameras this phone controls (monitor/master), dialled at their discovered addresses first, then the
     * stored ones; empty until the paired list is loaded.
     */
    fun controlTargets(): List<RemoteCameraTarget> = devices.value.filter { it.role == DeviceRole.CAMERA }.map { device ->
        RemoteCameraTarget(device.id, device.name, device.keyBytes(), endpoints = {
            // Always the camera's control port: an advert may carry the pairing or session port of the same phone.
            PairingClient.controlEndpoints(addressBook?.endpointsFor(device.id, device.endpoints) ?: device.endpoints)
        })
    }

    /** What this phone is for paired phone [deviceId] (D80). */
    fun relation(deviceId: String): Relation =
        devices.value.firstOrNull { it.id == deviceId }?.let { SessionRoles.relation(role.value, it.role) } ?: Relation.NONE

    /** This phone takes [choice] with [deviceId]; the other phone is asked first and follows. */
    fun choose(deviceId: String, choice: RoleChoice) {
        val current = session ?: return
        if (_roleProgress.value == RoleProgress.Working) return
        _roleProgress.value = RoleProgress.Working
        viewModelScope.launch {
            val hosts = addressBook?.endpointsFor(deviceId, emptyList()).orEmpty().map { it.host }
            _roleProgress.value = when (val outcome = current.choose(deviceId, choice, hosts)) {
                is ChooseOutcome.Done -> RoleProgress.Done(outcome.own)
                ChooseOutcome.Busy -> RoleProgress.Error(R.string.multicam_roles_busy_here)
                ChooseOutcome.PeerBusy -> RoleProgress.Error(R.string.multicam_roles_busy_peer)
                ChooseOutcome.NotPaired -> RoleProgress.Error(R.string.multicam_roles_not_paired)
                ChooseOutcome.Unreachable -> RoleProgress.Error(R.string.multicam_roles_unreachable)
            }
        }
    }

    fun resetRoleProgress() {
        _roleProgress.value = RoleProgress.Idle
    }

    fun consumeOpenControl() {
        session?.consumeOpenControl()
    }

    /** This phone as a controller, for the control links. */
    fun controllerIdentity(): LocalIdentity? =
        loaded?.let { LocalIdentity(it.ownId, deviceName, if (role.value == MulticamRole.MASTER) DeviceRole.MASTER else DeviceRole.MONITOR) }

    /** The session's common clock now (ns), or null outside a synced session. */
    fun commonNow(): Long? = ActiveCommonClock.current?.toCommon(elapsedNanos())

    /** "REC all" on a timecode frame boundary of the common clock (core-net SyncedStart, D58); immediate without one. */
    val syncedStart: SyncedStart = SyncedStart { rtts ->
        commonNow()?.let { now -> CoreSyncedStart.plan(now, rtts, null, TimeZone.getDefault().getOffset(nowMs()) / MS_PER_S) }
    }

    fun setRole(role: MulticamRole?) = viewModelScope.launch { roles.setMulticamRole(role) }

    /** Either phone: starts the pairing listener and shows a code; [stopShowingCode] when the screen leaves. */
    fun startShowingCode() = viewModelScope.launch {
        val repo = repository.await()
        val newHost = host ?: PairingHost(
            scope = viewModelScope,
            devices = repo,
            discovery = newDiscovery(),
            identity = { LocalIdentity(repo.ownId, deviceName, ownRole()) }
        ).also { host = it }
        _hostState.value = newHost.state
        newHost.start()
        ticker?.cancel()
        ticker = launch(background) {
            while (isActive) {
                delay(TICK_MS)
                newHost.tick()
            }
        }
    }

    fun newCode() = viewModelScope.launch(background) { host?.showNewCode() }

    fun stopShowingCode() {
        ticker?.cancel()
        ticker = null
        host?.stop()
        PairingWindow.shared.close()
    }

    fun resetProgress() {
        _progress.value = PairingProgress.Idle
    }

    /** Either phone: a scanned QR text. Ignored while a pairing is running. */
    fun onQrScanned(text: String) {
        if (_progress.value == PairingProgress.Working) return
        viewModelScope.launch {
            val repo = repository.await()
            when (val parsed = PairingPayload.parse(text, nowMs(), repo.ownId)) {
                is PairingParseResult.Invalid -> _progress.value = PairingProgress.Error(ControlStrings.of(parsed.error))
                is PairingParseResult.Ok -> pair(repo, parsed.payload.deviceId, parsed.payload.endpoints) { parsed.payload.token }
            }
        }
    }

    /** Either phone: the code typed for a phone found on the LAN. */
    fun pairWithCode(device: NearbyDevice, input: String) {
        if (_progress.value == PairingProgress.Working) return
        val code = ShortCode.normalize(input) ?: run {
            _progress.value = PairingProgress.Error(R.string.multicam_code_invalid)
            return
        }
        viewModelScope.launch {
            pair(repository.await(), device.deviceId, device.endpoints) { ShortCode.token(device.deviceId, code) }
        }
    }

    /** Removes [deviceId]; a monitor or master that loses a camera this way follows (master → monitor → off). */
    fun unpair(deviceId: String) = viewModelScope.launch(io) {
        val repo = repository.await()
        val wasCamera = repo.storedRoles()[deviceId] == DeviceRole.CAMERA
        repo.remove(deviceId)
        val own = role.value
        if (wasCamera && own != null && own != MulticamRole.CAMERA) {
            roles.setMulticamRole(SessionRoles.controllerRole(repo.storedRoles().values.count { it == DeviceRole.CAMERA }))
        }
    }

    /** Multicamera off on this phone: no role, and no paired phone is its camera any more (pairings are kept). */
    fun turnOff() = viewModelScope.launch(io) {
        val repo = repository.await()
        repo.setRoles(repo.storedRoles().mapValues { SessionRoles.NEUTRAL })
        roles.setMulticamRole(null)
    }

    private suspend fun pair(repo: PairedDevicesRepository, target: String, endpoints: List<Endpoint>, psk: () -> ByteArray) {
        _progress.value = PairingProgress.Working
        val local = LocalIdentity(repo.ownId, deviceName, ownRole())
        val outcome = withContext(io) { PairingClient(repo).pair(local, target, endpoints, psk()) }
        _progress.value = when (outcome) {
            is PairOutcome.Paired -> PairingProgress.Done(outcome.device.name, outcome.device.id)
            is PairOutcome.Refused -> PairingProgress.Error(ControlStrings.of(outcome.code))
            PairOutcome.Unreachable -> PairingProgress.Error(R.string.multicam_pair_unreachable)
            PairOutcome.NotStored -> PairingProgress.Error(R.string.multicam_pair_not_stored)
        }
    }

    /** This phone's role in a handshake: any role pairs with any (D80); without one, the neutral role. */
    private fun ownRole(): DeviceRole = role.value?.toDeviceRole() ?: SessionRoles.NEUTRAL

    /** "Last seen" of paired phones advertising now; written at most once a minute per phone. */
    private fun touchSeen(repo: PairedDevicesRepository, status: DiscoveryStatus) {
        val now = nowMs()
        val stale = repo.devices.value.filter { status.isOnline(it.id) && now - (it.lastSeenEpochMs ?: 0L) > SEEN_WRITE_MS }
        if (stale.isNotEmpty()) viewModelScope.launch(io) { stale.forEach { repo.touch(it.id) } }
    }

    override fun onCleared() {
        stopShowingCode()
        watcher.stop()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MS_PER_S = 1_000
        const val SEEN_WRITE_MS = 60_000L
    }
}
