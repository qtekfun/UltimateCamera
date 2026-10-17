package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.ControlConnection
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RejectCode
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.control.Session
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Coroutine glue between sockets and the pure role machines. Every call into a machine happens on one confined
 * dispatcher (e.g. Dispatchers.Default.limitedParallelism(1)), so the machines need no locks; blocking socket reads run
 * on Dispatchers.IO and hop back to it with each message.
 */

/** Tries [endpoints] in order with a fresh handshake each; a handshake refusal is final (another address would not help). */
fun endpointDialer(endpoints: () -> List<Endpoint>, handshake: () -> ClientHandshake): suspend () -> ControlConnection = {
    var last: Exception = IOException("no endpoint")
    var result: ControlConnection? = null
    for (endpoint in endpoints()) {
        try {
            result = ControlSockets.connect(endpoint.host, endpoint.port, handshake())
            break
        } catch (e: IOException) {
            last = e
        }
    }
    result ?: throw last
}

/** Handshake refusals after which redialling is pointless until the user pairs again. */
fun isPermanentRefusal(error: Throwable): Boolean =
    error is HandshakeException && error.code in setOf(RejectCode.UNKNOWN_DEVICE, RejectCode.BAD_PROOF, RejectCode.VERSION, RejectCode.ROLE_NOT_ALLOWED)

/** Runs a [ControllerLink]: dials when it asks, pumps messages, ticks. */
class ControllerDriver(
    private val link: ControllerLink,
    private val dial: suspend () -> ControlConnection,
    private val onSession: (Session) -> Unit = {},
    private val tickMs: Long = 100
) {
    fun launchIn(scope: CoroutineScope, confined: CoroutineDispatcher): Job = scope.launch(confined) {
        link.start()
        try {
            while (isActive) {
                if (link.pollDial()) launch { connectOnce(confined) }
                link.tick()
                delay(tickMs)
            }
        } finally {
            link.stop()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Dial boundary: any failure is a failed attempt and a redial.
    private suspend fun connectOnce(confined: CoroutineDispatcher) {
        val connection = try {
            withContext(Dispatchers.IO) { dial() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            link.onDialFailed(permanent = isPermanentRefusal(e))
            return
        }
        onSession(connection.session)
        val sink = ConnectionSink(connection)
        link.onConnected(sink)
        if (link.state != LinkState.CONNECTED) return
        try {
            while (true) {
                val message = withContext(Dispatchers.IO) { connection.receive() } ?: break
                withContext(confined) { link.onMessage(message) }
            }
        } catch (_: IOException) {
            // Broken or forged frame: treated like a drop.
        } finally {
            connection.close()
            withContext(confined + kotlinx.coroutines.NonCancellable) { link.onDisconnected(sink) }
        }
    }
}

/**
 * Runs a [CameraNode]: accepts authenticated controllers on [port], pumps their messages and ticks precisely enough for
 * scheduled starts (it wakes at [CameraNode.nextDeadline]). [onPaired] receives sessions opened with a pairing code,
 * whose new pair key the app stores.
 */
class CameraDriver(
    private val node: CameraNode,
    private val clock: MonotonicClock,
    private val local: LocalIdentity,
    private val keyring: Keyring,
    private val port: Int,
    private val onPaired: (Session) -> Unit = {},
    private val onSessionOpened: (Session) -> Unit = {},
    private val onBound: (Int) -> Unit = {},
    private val tickMs: Long = 50
) {
    fun launchIn(scope: CoroutineScope, confined: CoroutineDispatcher): Job = scope.launch(confined) {
        val driver = this
        val open = ConcurrentHashMap.newKeySet<ControlConnection>()
        launch(Dispatchers.IO) {
            ControlSockets.serve(
                port = port,
                handshakeFactory = { ServerHandshake(local, keyring) },
                onSession = { connection ->
                    open += connection
                    driver.launch { handle(connection, confined) }.invokeOnCompletion { open -= connection }
                },
                onBound = onBound
            )
        }
        try {
            while (isActive) {
                node.tick()
                val wait = node.nextDeadline?.let { ((it - clock.nanos()) / NANOS_PER_MS).coerceIn(0, tickMs) } ?: tickMs
                delay(wait)
            }
        } finally {
            open.forEach { it.close() } // unblocks the readers
        }
    }

    private suspend fun handle(connection: ControlConnection, confined: CoroutineDispatcher) {
        val session = connection.session
        if (session.newPairKey != null) onPaired(session)
        onSessionOpened(session)
        val sink = ConnectionSink(connection)
        node.onPeerConnected(session.peerId, session.peerRole, sink)
        try {
            while (true) {
                val message = withContext(Dispatchers.IO) { connection.receive() } ?: break
                withContext(confined) { node.onMessage(session.peerId, message) }
            }
        } catch (_: IOException) {
            // Broken or forged frame: the peer is dropped; recording continues.
        } finally {
            connection.close()
            withContext(confined + kotlinx.coroutines.NonCancellable) { node.onPeerDisconnected(session.peerId, sink) }
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
