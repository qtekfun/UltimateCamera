package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.ControlConnection
import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RejectCode
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice
import com.qtekfun.ultimatecamera.feature.multicam.session.SessionRoles
import java.io.IOException

/** How a pairing attempt from this phone ended. */
sealed interface PairOutcome {
    data class Paired(val device: PairedDevice) : PairOutcome

    /** The other phone refused the handshake (wrong code, code no longer shown, role...). */
    data class Refused(val code: RejectCode) : PairOutcome

    /** No endpoint answered. */
    data object Unreachable : PairOutcome

    /** Paired, but the key could not be stored on this phone. */
    data object NotStored : PairOutcome
}

/**
 * Scanning side of pairing (either phone since D80, REQ-NET-002): runs the PAIR handshake with the token from the QR (or
 * derived from the typed code) against each candidate endpoint and stores the resulting pair key. The pairing
 * connection is closed at once; the control link later dials the camera's control port with RESUME.
 */
class PairingClient(
    private val devices: PairedDevicesRepository,
    private val connect: suspend (Endpoint, ClientHandshake) -> ControlConnection = { e, h -> ControlSockets.connect(e.host, e.port, h) }
) {
    suspend fun pair(local: LocalIdentity, target: String, endpoints: List<Endpoint>, psk: ByteArray): PairOutcome {
        var last: PairOutcome = PairOutcome.Unreachable
        for (endpoint in endpoints.distinct()) {
            val outcome = attempt(endpoint, local, target, psk, endpoints)
            // Unreachable, or another listener of the same phone that does not hold the code: try the next address.
            if (outcome != PairOutcome.Unreachable && outcome != PairOutcome.Refused(RejectCode.PAIRING_CLOSED)) return outcome
            if (outcome != PairOutcome.Unreachable) last = outcome
        }
        return last
    }

    @Suppress("TooGenericExceptionCaught") // Storage boundary.
    private suspend fun attempt(endpoint: Endpoint, local: LocalIdentity, target: String, psk: ByteArray, all: List<Endpoint>): PairOutcome {
        val connection = try {
            connect(endpoint, ClientHandshake(local, HandshakeMode.PAIR, target, psk))
        } catch (_: IOException) {
            return PairOutcome.Unreachable
        } catch (e: HandshakeException) {
            return PairOutcome.Refused(e.code)
        }
        return connection.use { c ->
            try {
                // Neutral role: the roles are chosen after pairing (D80).
                devices.addFromSession(c.session, controlEndpoints(all), SessionRoles.NEUTRAL)?.let { PairOutcome.Paired(it) }
                    ?: PairOutcome.Refused(RejectCode.PROTOCOL)
            } catch (_: RuntimeException) {
                // Keystore may throw anything (ProviderException...): an error on screen, not a crash.
                PairOutcome.NotStored
            }
        }
    }

    companion object {
        /** The hosts the camera was reached at, on its control port (the pairing listener uses a temporary port). */
        fun controlEndpoints(endpoints: List<Endpoint>): List<Endpoint> = endpoints.map { Endpoint(it.host, ControlProtocol.DEFAULT_PORT) }.distinct()
    }
}
