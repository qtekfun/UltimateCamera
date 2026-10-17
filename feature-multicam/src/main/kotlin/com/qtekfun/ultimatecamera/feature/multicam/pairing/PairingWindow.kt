package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the pairing screen of the showing phone displays. */
sealed interface WindowState {
    data object Closed : WindowState

    /** A code is on screen until [expiresAtMs] (wall clock). */
    data class Open(val code: String, val expiresAtMs: Long) : WindowState

    /** A phone used the code (successfully or not); the code is gone and a new one must be shown. */
    data object Used : WindowState
}

/**
 * The pairing code currently shown by this phone (D55). One use: the first PAIR handshake that asks for the token
 * ([takeToken]) closes the window, whether its proof then verifies or not, so a captured exchange cannot be followed
 * by a second guess. Thread-safe (the control server asks from its IO threads).
 */
class PairingWindow(private val nowMs: () -> Long = System::currentTimeMillis) {
    private class Shown(val code: String, val token: ByteArray, val expiresAtMs: Long)

    private val lock = Any()
    private var shown: Shown? = null
    private val _state = MutableStateFlow<WindowState>(WindowState.Closed)
    val state: StateFlow<WindowState> = _state.asStateFlow()

    /** Shows [code] (whose token is [token]) for [lifetimeMs]; replaces any previous code. */
    fun open(code: String, token: ByteArray, lifetimeMs: Long = DEFAULT_LIFETIME_MS): WindowState.Open = synchronized(lock) {
        val expiry = nowMs() + lifetimeMs
        shown = Shown(code, token.copyOf(), expiry)
        WindowState.Open(code, expiry).also { _state.value = it }
    }

    fun close() = synchronized(lock) {
        shown = null
        _state.value = WindowState.Closed
    }

    /** The token, once, while the code is shown and not expired; null otherwise. */
    fun takeToken(): ByteArray? = synchronized(lock) {
        val current = shown ?: return null
        shown = null
        if (nowMs() > current.expiresAtMs) {
            _state.value = WindowState.Closed
            null
        } else {
            _state.value = WindowState.Used
            current.token
        }
    }

    /** Closes an expired code (the screen calls it on its countdown); true when it was open and has expired. */
    fun expireIfDue(): Boolean = synchronized(lock) {
        val current = shown ?: return false
        if (nowMs() <= current.expiresAtMs) return false
        shown = null
        _state.value = WindowState.Closed
        true
    }

    companion object {
        /** Same lifetime as the QR payload of core-net. */
        const val DEFAULT_LIFETIME_MS = 300_000L

        /** The window of this process: the pairing screen opens it, every control server of the camera role reads it. */
        val shared: PairingWindow by lazy { PairingWindow() }
    }
}

/**
 * [Keyring] for a control server of this phone: pair keys from [devices] and the one-use token of [window]. The camera
 * role's server should use it (with `CameraDriver.onPaired` storing the new key through [PairedDevicesRepository.addFromSession])
 * so a pairing also works when it lands there instead of on the pairing screen's own listener.
 */
class PairingKeyring(
    private val devices: PairedDevicesRepository,
    private val window: PairingWindow = PairingWindow.shared,
    private val accepted: Set<DeviceRole> = setOf(DeviceRole.MASTER, DeviceRole.MONITOR)
) : Keyring {
    override fun pairKey(deviceId: String): ByteArray? = devices.pairKey(deviceId)

    override fun pairingToken(): ByteArray? = window.takeToken()

    override fun accepts(role: DeviceRole): Boolean = role in accepted
}
