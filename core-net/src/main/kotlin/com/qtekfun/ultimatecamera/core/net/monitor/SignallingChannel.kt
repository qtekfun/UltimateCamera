package com.qtekfun.ultimatecamera.core.net.monitor

import java.io.Closeable
import java.io.IOException
import java.io.Reader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One signalling connection: [SignallingCodec] lines over TCP. [send] and [receive] block, so call them off the main
 * thread; [send] may be called from several threads, [receive] from one reader. [close] from any thread unblocks a
 * pending [receive] (it then throws an [IOException]).
 */
internal class SignallingChannel private constructor(private val socket: Socket) : Closeable {
    val remote: String = socket.remoteSocketAddress.toString().removePrefix("/")
    private val reader: Reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
    private val writeLock = Any()
    private val line = StringBuilder()

    fun send(message: SignallingMessage) {
        val text = SignallingCodec.encode(message)
        synchronized(writeLock) {
            writer.write(text)
            writer.write('\n'.code)
            writer.flush()
        }
    }

    /** The next message, or null when the peer closed the connection. */
    fun receive(): SignallingMessage? {
        line.setLength(0)
        while (true) {
            val c = reader.read()
            when {
                c < 0 -> return if (line.isEmpty()) null else throw IOException("connection closed mid-message")
                c == '\n'.code -> if (line.isNotBlank()) return SignallingCodec.decode(line.toString())
                line.length >= SignallingCodec.MAX_LINE_CHARS -> throw IOException("signalling line too long")
                else -> line.append(c.toChar())
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_PORT = 47_801

        /** Waits, cancellably, for one peer to connect on [port] and returns its channel; the listening socket closes. */
        suspend fun accept(port: Int = DEFAULT_PORT): SignallingChannel = withContext(Dispatchers.IO) {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                server.soTimeout = ACCEPT_POLL_MS
                var socket: Socket? = null
                while (socket == null) {
                    coroutineContext.ensureActive()
                    try {
                        socket = server.accept()
                    } catch (_: SocketTimeoutException) {
                        // Check for cancellation and keep listening.
                    }
                }
                SignallingChannel(socket.apply { tcpNoDelay = true })
            }
        }

        suspend fun connect(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = CONNECT_TIMEOUT_MS): SignallingChannel = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                SignallingChannel(socket)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }

        private const val ACCEPT_POLL_MS = 500
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
