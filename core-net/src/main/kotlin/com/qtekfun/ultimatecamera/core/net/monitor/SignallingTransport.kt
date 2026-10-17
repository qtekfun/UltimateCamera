package com.qtekfun.ultimatecamera.core.net.monitor

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a monitor-link session sends and receives its [SignallingMessage]s: the authenticated control channel
 * ([QueueSignallingTransport], D70) or, for peers without that feature, the legacy plain socket on 47801.
 * [send] and [receive] may block: call them off the main thread. [close] from any thread unblocks [receive].
 */
interface SignallingTransport : Closeable {
    /** Who is on the other end, for the result (an address or a device id). */
    val remote: String

    /** Throws [IOException] when the transport is closed or broken. */
    fun send(message: SignallingMessage)

    /** The next message, or null once the transport is closed. */
    fun receive(): SignallingMessage?
}

/**
 * A [SignallingTransport] fed by someone else's reader: the control-channel glue calls [deliver] with every
 * `ControlMessage.Signal` of this session and sends through [out] (which returns false when the link is gone).
 * Nothing here touches a socket, so the session inherits the control channel's authentication and encryption.
 */
class QueueSignallingTransport(override val remote: String, private val out: (SignallingMessage) -> Boolean) : SignallingTransport {
    private val inbox = LinkedBlockingQueue<Any>()
    private val _closed = MutableStateFlow(false)

    /** True once [close] ran (the peer left, a newer session replaced this one, or the session ended). */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    /** Queues an inbound message; false (dropped) after [close]. Never blocks. */
    fun deliver(message: SignallingMessage): Boolean = !_closed.value && inbox.offer(message)

    override fun send(message: SignallingMessage) {
        if (_closed.value || !out(message)) throw IOException("signalling link closed")
    }

    override fun receive(): SignallingMessage? {
        if (_closed.value && inbox.isEmpty()) return null
        val next = inbox.take()
        return if (next === END) null else next as SignallingMessage
    }

    override fun close() {
        if (_closed.compareAndSet(expect = false, update = true)) inbox.offer(END)
    }

    private companion object {
        val END = Any()
    }
}
