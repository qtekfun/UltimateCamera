package com.qtekfun.ultimatecamera.feature.multicam.session

import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.ControlConnection
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RejectCode
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.control.Session
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The multicamera session socket (D80): every phone with paired phones listens on [DEFAULT_PORT] while the app is in
 * the foreground, so the roles can be chosen on either phone after pairing. Only RESUME handshakes with a stored pair
 * key pass (the same mutual authentication and AES-GCM framing as the control channel, D31); the pairing code is not
 * accepted here. One request per connection: a [ControlMessage.Roles], answered with an Ack or an Error.
 */
object SessionSocket {
    const val DEFAULT_PORT = 47_805

    /** A request (or its answer) not received within this time ends the connection. */
    const val READ_TIMEOUT_MS = 5_000L

    /** How a role request sent to another phone ended. */
    sealed interface SendResult {
        data object Ok : SendResult

        /** The other phone refused: [ErrorCode.BUSY] while it records, [ErrorCode.INVALID_ARGUMENT] for a bad pair. */
        data class Refused(val code: ErrorCode) : SendResult

        /** The other phone no longer knows this one (it unpaired): pair again. */
        data object NotPaired : SendResult

        /** No address answered (app closed, other network). */
        data object Unreachable : SendResult
    }

    /** Keyring of the session server: pair keys only (no pairing code), any role. */
    fun keyring(pairKey: (String) -> ByteArray?): Keyring = object : Keyring {
        override fun pairKey(deviceId: String): ByteArray? = pairKey(deviceId)

        override fun pairingToken(): ByteArray? = null

        override fun accepts(role: DeviceRole): Boolean = true
    }

    /**
     * Listens on [port] until cancelled. [onRoles] decides the answer to a request of an authenticated peer ([Session]
     * and its host as the socket saw it); it runs on an IO thread.
     */
    suspend fun serve(
        port: Int,
        local: () -> LocalIdentity,
        pairKey: (String) -> ByteArray?,
        onRoles: suspend (session: Session, host: String, request: ControlMessage.Roles) -> ControlMessage,
        onBound: (Int) -> Unit = {}
    ) = coroutineScope {
        val scope: CoroutineScope = this
        ControlSockets.serve(
            port = port,
            handshakeFactory = { ServerHandshake(local(), keyring(pairKey)) },
            onSession = { connection -> scope.launch(Dispatchers.IO) { answer(connection, onRoles) } },
            onBound = onBound
        )
    }

    @Suppress("TooGenericExceptionCaught") // Network boundary: a bad request ends that connection, never the server.
    private suspend fun answer(connection: ControlConnection, onRoles: suspend (Session, String, ControlMessage.Roles) -> ControlMessage) {
        try {
            val request = receive(connection)
            if (request is ControlMessage.Roles) {
                connection.send(onRoles(connection.session, hostOf(connection.remote), request))
            } else {
                connection.send(ControlMessage.Error(null, ErrorCode.UNKNOWN_MESSAGE, "session socket takes roles only"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Broken, forged or late request: this connection only.
        } finally {
            connection.close()
        }
    }

    /** Sends [request] to [peerId] at the first of [hosts] that answers and waits for its answer. */
    suspend fun send(
        local: LocalIdentity,
        peerId: String,
        pairKey: ByteArray,
        hosts: List<String>,
        request: ControlMessage.Roles,
        port: Int = DEFAULT_PORT
    ): SendResult {
        for (host in hosts.distinct()) {
            val result = sendTo(host, port, ClientHandshake(local, HandshakeMode.RESUME, peerId, pairKey), request)
            if (result != null) return result
        }
        return SendResult.Unreachable
    }

    /** One address: the answer, or null to try the next address (not listening, or a refusal another address may not give). */
    private suspend fun sendTo(host: String, port: Int, handshake: ClientHandshake, request: ControlMessage.Roles): SendResult? {
        val connection = try {
            ControlSockets.connect(host, port, handshake)
        } catch (_: IOException) {
            return null
        } catch (e: HandshakeException) {
            return if (e.code == RejectCode.UNKNOWN_DEVICE || e.code == RejectCode.BAD_PROOF) SendResult.NotPaired else null
        }
        return try {
            connection.send(request)
            when (val reply = receive(connection)) {
                is ControlMessage.Ack -> if (reply.commandId == request.id) SendResult.Ok else SendResult.Refused(ErrorCode.INVALID_ARGUMENT)
                is ControlMessage.Error -> SendResult.Refused(reply.code)
                else -> SendResult.Unreachable
            }
        } catch (_: IOException) {
            SendResult.Unreachable
        } finally {
            connection.close()
        }
    }

    /** One message, or null; the connection is closed when nothing arrives in time (that unblocks the read). */
    private suspend fun receive(connection: ControlConnection): ControlMessage? = coroutineScope {
        val watchdog = launch {
            delay(READ_TIMEOUT_MS)
            connection.close()
        }
        try {
            (withContext(Dispatchers.IO) { connection.receive() } as? Decoded.Message)?.message
        } finally {
            watchdog.cancel()
        }
    }

    /** `host` of a `host:port` (IPv6 hosts may come in brackets). */
    fun hostOf(remote: String): String = remote.substringBeforeLast(':').removePrefix("/").removePrefix("[").removeSuffix("]")
}
