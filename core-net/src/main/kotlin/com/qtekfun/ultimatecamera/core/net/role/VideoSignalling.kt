package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.monitor.QueueSignallingTransport
import com.qtekfun.ultimatecamera.core.net.monitor.SignallingMessage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** A controller asked the camera for the monitor video over the control channel (D70): run a `MonitorSender` on [transport]. */
class VideoRequest(val peerId: String, val transport: QueueSignallingTransport)

/**
 * Camera side of the video signalling inside the control channel (D70): a [SignalListener] for [CameraNode] that turns a
 * controller's [SignallingMessage.Hello] into a [VideoRequest] with its own transport, routes that peer's later signals
 * to it, and closes it when the peer leaves or opens a newer session. [send] carries the camera's signals back to the
 * peer (it must hop to the node's thread, e.g. `CameraSignalling.send`, and never block).
 *
 * Only authenticated controllers reach the node, so an unpaired phone never gets a request through here; the legacy
 * socket is not involved.
 */
class VideoSignalHub(private val send: (peerId: String, message: SignallingMessage) -> Boolean) : SignalListener {
    private val sessions = ConcurrentHashMap<String, QueueSignallingTransport>()
    private val pending = Channel<VideoRequest>(Channel.UNLIMITED)

    /** Video sessions asked for, in order. A request whose transport is already closed was superseded: skip it. */
    val requests: ReceiveChannel<VideoRequest> get() = pending

    override fun onSignal(peerId: String, message: SignallingMessage) {
        if (message is SignallingMessage.Hello) {
            sessions.remove(peerId)?.close()
            val transport = QueueSignallingTransport(peerId) { send(peerId, it) }
            transport.deliver(message)
            sessions[peerId] = transport
            pending.trySend(VideoRequest(peerId, transport))
        } else {
            sessions[peerId]?.deliver(message)
        }
    }

    override fun onPeerGone(peerId: String) {
        sessions.remove(peerId)?.close()
    }

    /** Ends every session; no request arrives afterwards. */
    fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        pending.close()
    }
}

/** How a controller gets the monitor video of a camera (D70). */
sealed interface VideoRoute {
    /** Not connected yet, or the camera's features are not known yet: wait. */
    data object None : VideoRoute

    /** Signalling inside the authenticated control channel. */
    class Control(val transport: QueueSignallingTransport) : VideoRoute

    /** An older camera: the legacy socket on 47801 at [host] (the camera only accepts it from authenticated peers). */
    data class Legacy(val host: String) : VideoRoute
}

/**
 * Controller side of the video signalling (D70): one session at a time over [link]. [route], [onSignal] and
 * [onLinkState] run on the link's thread; [onLinkThread] runs a block there (the transport's sends come from
 * libwebrtc's threads).
 */
class ControllerSignalling(private val link: ControllerLink, private val onLinkThread: (() -> Unit) -> Unit) {
    @Volatile private var current: QueueSignallingTransport? = null

    /** A new session's route (the previous session ends); [legacyHost] is used only for a camera without the feature. */
    fun route(legacyHost: String?): VideoRoute {
        current?.close()
        current = null
        if (link.state != LinkState.CONNECTED) return VideoRoute.None
        val features = link.cameraFeatures ?: return VideoRoute.None
        if (!link.supportsSignalling) return if (legacyHost != null && features.isEmpty()) VideoRoute.Legacy(legacyHost) else VideoRoute.None
        val transport = QueueSignallingTransport(link.cameraId) { message ->
            onLinkThread { link.sendSignal(message) }
            true
        }
        current = transport
        return VideoRoute.Control(transport)
    }

    fun onSignal(message: SignallingMessage) {
        current?.deliver(message)
    }

    /** A dropped control link ends the session: signalling may only flow while the peer is authenticated. */
    fun onLinkState(state: LinkState) {
        if (state != LinkState.CONNECTED) {
            current?.close()
            current = null
        }
    }
}
