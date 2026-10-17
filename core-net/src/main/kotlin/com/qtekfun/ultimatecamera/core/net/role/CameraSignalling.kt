package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.control.ControlFeatures
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.monitor.SignallingMessage

/** What the camera app does with WebRTC signalling that arrived over the control channel (D70). On the node's thread. */
interface SignalListener {
    /** [peerId], an authenticated controller that advertised [ControlFeatures.SIGNALLING], sent [message]. */
    fun onSignal(peerId: String, message: SignallingMessage) {}

    /** [peerId] left (disconnected, replaced or unpaired): its video session, if any, must end. */
    fun onPeerGone(peerId: String) {}

    companion object {
        val NONE = object : SignalListener {}
    }
}

/**
 * The control-channel side of the monitor video signalling on the camera (D70), owned by [CameraNode] and only touched
 * on its thread. It learns each peer's optional features from its first message ([ControlMessage.Features], or
 * anything else for a peer without them), passes [ControlMessage.Signal]s of peers that advertised
 * [ControlFeatures.SIGNALLING] to the [SignalListener] and sends the camera's signals back. Only authenticated peers
 * ever reach the node, so nothing here can be reached without a pairing.
 */
class CameraSignalling internal constructor(private val listener: SignalListener) {
    private val sinks = HashMap<String, MessageSink>()

    /** Features by peer, once its first message arrived. */
    private val features = HashMap<String, Set<String>>()

    /** Connected peers known to lack control-channel signalling: only they may use the legacy socket on 47801. */
    val legacyPeers: Set<String> get() = features.filterValues { ControlFeatures.SIGNALLING !in it }.keys.toSet()

    /** Connected peers that signal over the control channel. */
    val signallingPeers: Set<String> get() = features.filterValues { ControlFeatures.SIGNALLING in it }.keys.toSet()

    /** Sends [message] to [peerId] inside its control session; false when it is not connected or lacks the feature. */
    fun send(peerId: String, message: SignallingMessage): Boolean {
        val sink = sinks[peerId] ?: return false
        if (ControlFeatures.SIGNALLING !in features[peerId].orEmpty()) return false
        return sink.send(ControlMessage.Signal(message))
    }

    internal fun connected(peerId: String, sink: MessageSink) {
        if (sinks.containsKey(peerId)) gone(peerId)
        sinks[peerId] = sink
        features.remove(peerId)
    }

    internal fun gone(peerId: String) {
        val known = sinks.remove(peerId) != null
        features.remove(peerId)
        if (known) listener.onPeerGone(peerId)
    }

    internal fun observe(peerId: String, decoded: Decoded) {
        if (peerId !in sinks) return
        val message = (decoded as? Decoded.Message)?.message
        if (message is ControlMessage.Features) {
            features[peerId] = message.features
            return
        }
        // A peer that does not open with its features has none (an older build): legacy video only.
        if (peerId !in features) features[peerId] = emptySet()
        if (message is ControlMessage.Signal && ControlFeatures.SIGNALLING in features.getValue(peerId)) listener.onSignal(peerId, message.message)
    }
}
