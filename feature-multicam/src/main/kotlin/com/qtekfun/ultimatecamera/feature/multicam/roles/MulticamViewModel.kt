package com.qtekfun.ultimatecamera.feature.multicam.roles

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.SyncedStart as CoreSyncedStart
import com.qtekfun.ultimatecamera.core.net.control.ControlStrings
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.discovery.NsdServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.discovery.PeerAddressBook
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
import java.util.TimeZone
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

    data class Done(val name: String) : PairingProgress

    data class Error(@param:StringRes val message: Int) : PairingProgress
}

/** Role choice, pairing (both sides), paired devices and live discovery for the multicamera screens (REQ-NET-002/003). */
@OptIn(ExperimentalCoroutinesApi::class)
class MulticamViewModel(application: Application, private val roles: MulticamRoleRepository) : AndroidViewModel(application) {
    private val repository = viewModelScope.async(Dispatchers.IO) { PairedDevicesRepository.getInstance(application) }

    @Volatile private var addressBook: PeerAddressBook? = null

    @Volatile private var loaded: PairedDevicesRepository? = null
    private val watcher = DiscoveryWatcher(NsdServiceDiscovery(application)) { event -> addressBook?.onEvent(event) }
    private var host: PairingHost? = null
    private var ticker: Job? = null
    private val deviceName: String = deviceName(application)

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
            addressBook?.endpointsFor(device.id, device.endpoints) ?: device.endpoints
        })
    }

    /** This phone as a controller, for the control links. */
    fun controllerIdentity(): LocalIdentity? =
        loaded?.let { LocalIdentity(it.ownId, deviceName, if (role.value == MulticamRole.MASTER) DeviceRole.MASTER else DeviceRole.MONITOR) }

    /** The session's common clock now (ns), or null outside a synced session. */
    fun commonNow(): Long? = ActiveCommonClock.current?.toCommon(SystemClock.elapsedRealtimeNanos())

    /** "REC all" on a timecode frame boundary of the common clock (core-net SyncedStart, D58); immediate without one. */
    val syncedStart: SyncedStart = SyncedStart { rtts ->
        commonNow()?.let { now -> CoreSyncedStart.plan(now, rtts, null, TimeZone.getDefault().getOffset(System.currentTimeMillis()) / MS_PER_S) }
    }

    fun setRole(role: MulticamRole?) = viewModelScope.launch { roles.setMulticamRole(role) }

    /** Camera side: starts the pairing listener and shows a code; [stopShowingCode] when the screen leaves. */
    fun startShowingCode() = viewModelScope.launch {
        val repo = repository.await()
        val newHost = host ?: PairingHost(
            scope = viewModelScope,
            devices = repo,
            discovery = NsdServiceDiscovery(getApplication()),
            identity = { LocalIdentity(repo.ownId, deviceName, DeviceRole.CAMERA) }
        ).also { host = it }
        _hostState.value = newHost.state
        newHost.start()
        ticker?.cancel()
        ticker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(TICK_MS)
                newHost.tick()
            }
        }
    }

    fun newCode() = viewModelScope.launch(Dispatchers.Default) { host?.showNewCode() }

    fun stopShowingCode() {
        ticker?.cancel()
        ticker = null
        host?.stop()
        PairingWindow.shared.close()
    }

    fun resetProgress() {
        _progress.value = PairingProgress.Idle
    }

    /** Monitor/master side: a scanned QR text. Ignored while a pairing is running. */
    fun onQrScanned(text: String) {
        if (_progress.value == PairingProgress.Working) return
        viewModelScope.launch {
            val repo = repository.await()
            when (val parsed = PairingPayload.parse(text, System.currentTimeMillis(), repo.ownId)) {
                is PairingParseResult.Invalid -> _progress.value = PairingProgress.Error(ControlStrings.of(parsed.error))
                is PairingParseResult.Ok -> pair(repo, parsed.payload.deviceId, parsed.payload.endpoints) { parsed.payload.token }
            }
        }
    }

    /** Monitor/master side: the code typed for a camera found on the LAN. */
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

    fun unpair(deviceId: String) = viewModelScope.launch(Dispatchers.IO) { repository.await().remove(deviceId) }

    private suspend fun pair(repo: PairedDevicesRepository, target: String, endpoints: List<Endpoint>, psk: () -> ByteArray) {
        _progress.value = PairingProgress.Working
        val local = LocalIdentity(repo.ownId, deviceName, if (role.value == MulticamRole.MASTER) DeviceRole.MASTER else DeviceRole.MONITOR)
        val outcome = withContext(Dispatchers.IO) { PairingClient(repo).pair(local, target, endpoints, psk()) }
        _progress.value = when (outcome) {
            is PairOutcome.Paired -> PairingProgress.Done(outcome.device.name)
            is PairOutcome.Refused -> PairingProgress.Error(ControlStrings.of(outcome.code))
            PairOutcome.Unreachable -> PairingProgress.Error(R.string.multicam_pair_unreachable)
        }
    }

    /** "Last seen" of paired phones advertising now; written at most once a minute per phone. */
    private fun touchSeen(repo: PairedDevicesRepository, status: DiscoveryStatus) {
        val now = System.currentTimeMillis()
        val stale = repo.devices.value.filter { status.isOnline(it.id) && now - (it.lastSeenEpochMs ?: 0L) > SEEN_WRITE_MS }
        if (stale.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { stale.forEach { repo.touch(it.id) } }
    }

    override fun onCleared() {
        stopShowingCode()
        watcher.stop()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MS_PER_S = 1_000
        const val SEEN_WRITE_MS = 60_000L

        fun deviceName(application: Application): String = (Settings.Global.getString(application.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
            .trim()
            .ifEmpty { "UltimateCamera" }
            .take(PairingPayload.MAX_NAME_CHARS)
    }
}
