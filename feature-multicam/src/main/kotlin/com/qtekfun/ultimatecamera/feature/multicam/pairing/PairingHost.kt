package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.ControlConnection
import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceAdvert
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import com.qtekfun.ultimatecamera.feature.multicam.session.SessionRoles
import com.qtekfun.ultimatecamera.feature.multicam.session.SessionSocket
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the showing phone's pairing screen displays. */
sealed interface HostState {
    data object Idle : HostState

    /** Listening; [qrText] (a core-net pairing payload) and [code] are on screen until [expiresAtMs]. */
    data class Showing(val qrText: String, val code: String, val expiresAtMs: Long) : HostState

    /** No usable network address: nothing to put in the QR. */
    data object NoNetwork : HostState

    /** The listener could not start. */
    data object Failed : HostState

    /** [name] ([deviceId]) paired with this phone just now. */
    data class Paired(val name: String, val deviceId: String) : HostState
}

/**
 * Showing side of pairing (either phone since D80; REQ-NET-002, D55): a temporary control listener on a free port that only
 * completes PAIR handshakes, advertised over DNS-SD so a monitor typing the code can find it, and the QR + short code
 * for it. The code is one use: after any attempt a fresh one is shown. The key of a successful pairing is stored in
 * [devices] with the neutral role and the scanning phone's address (roles are chosen afterwards, D80); the connection is
 * then closed. Any role may pair. A failure to store the key is shown as [HostState.Failed], never a crash.
 */
class PairingHost(
    private val scope: CoroutineScope,
    private val devices: PairedDevicesRepository,
    private val discovery: ServiceDiscovery,
    private val identity: () -> LocalIdentity,
    private val addresses: () -> List<LocalEndpoints.Candidate> = LocalEndpoints::current,
    private val window: PairingWindow = PairingWindow.shared,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()
    private var server: Job? = null
    private var advert: Closeable? = null
    private var port = 0

    fun start() {
        if (server != null) return
        val local = identity()
        val keyring = PairingKeyring(devices, window, accepted = DeviceRole.entries.toSet())
        server = scope.launch {
            try {
                ControlSockets.serve(
                    port = 0,
                    handshakeFactory = { ServerHandshake(local, keyring) },
                    onSession = { connection -> _state.value = store(connection) },
                    onRejected = { _, _ -> if (window.state.value == WindowState.Used) showNewCode() },
                    onBound = { bound ->
                        port = bound
                        advert = runCatching {
                            discovery.advertise(ServiceAdvert(local.deviceId, local.name, local.role, bound, ControlProtocol.VERSION))
                        }.getOrNull()
                        showNewCode()
                    }
                )
            } catch (_: IOException) {
                _state.value = HostState.Failed
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Storage boundary (Keystore may throw anything): the screen says so.
    private fun store(connection: ControlConnection): HostState = connection.use {
        val session = it.session
        val back = Endpoint(SessionSocket.hostOf(it.remote), ControlProtocol.DEFAULT_PORT)
        try {
            devices.addFromSession(session, listOf(back), SessionRoles.NEUTRAL)?.let { d -> HostState.Paired(d.name, d.id) } ?: HostState.Failed
        } catch (_: RuntimeException) {
            HostState.Failed
        }
    }

    /** A fresh code (after expiry, a used code or a completed pairing). */
    fun showNewCode() {
        if (port == 0) return
        val local = identity()
        val endpoints: List<Endpoint> = LocalEndpoints.pick(addresses(), port)
        if (endpoints.isEmpty()) {
            window.close()
            _state.value = HostState.NoNetwork
            return
        }
        val code = ShortCode.generate()
        val token = ShortCode.token(local.deviceId, code)
        val opened = window.open(code, token)
        val payload = PairingPayload(
            deviceId = local.deviceId,
            name = local.name.take(PairingPayload.MAX_NAME_CHARS),
            role = local.role,
            endpoints = endpoints,
            token = token,
            expiresAtEpochSeconds = opened.expiresAtMs / MS_PER_S
        )
        _state.value = HostState.Showing(payload.encode(), code, opened.expiresAtMs)
    }

    /** Called every second by the screen: replaces an expired code. */
    fun tick() {
        if (window.expireIfDue() || (state.value as? HostState.Showing)?.let { nowMs() > it.expiresAtMs } == true) showNewCode()
    }

    fun stop() {
        window.close()
        advert?.let { runCatching { it.close() } }
        advert = null
        server?.cancel()
        server = null
        port = 0
        _state.value = HostState.Idle
    }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
