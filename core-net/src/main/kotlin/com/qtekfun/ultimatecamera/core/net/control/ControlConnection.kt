package com.qtekfun.ultimatecamera.core.net.control

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * AES-256-GCM for one direction of a session. The 96-bit nonce is the 64-bit frame counter (zero-padded); each direction
 * has its own key, so a nonce is never reused. The receiver expects the exact next counter: a replayed, dropped or
 * reordered frame fails authentication and the connection is closed (TCP never reorders, so that means tampering).
 */
internal class FrameCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private var counter = 0L

    fun seal(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(plain)

    fun open(sealed: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE).doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        throw IOException("control frame failed authentication", e)
    }

    private fun cipher(mode: Int): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(counter++).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, keySpec, GCMParameterSpec(TAG_BITS, nonce)) }
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** `u32 length ‖ bytes` frames over a stream. */
internal class FrameIo(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input.buffered())
    private val output = DataOutputStream(output.buffered())

    fun write(bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    /** Next frame, or null at a clean end of stream between frames. */
    fun read(maxBytes: Int): ByteArray? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length !in 0..maxBytes) throw IOException("frame of $length bytes (max $maxBytes)")
        return ByteArray(length).also { input.readFully(it) }
    }
}

/**
 * One authenticated, encrypted control session (protocol in `ControlMessage`, handshake in `Handshake`). Blocking:
 * call [receive] from one reader thread; [send] is thread-safe; [close] from any thread unblocks [receive].
 *
 * Why a separate TCP channel instead of the WebRTC data channel or the spike signalling socket (port 47801): control
 * must work without video (a master with several cameras and no preview, or a monitor whose video failed), with the
 * camera recording and the media stack possibly restarting; reconnection and state resync are then independent of
 * ICE/DTLS. The WebRTC offer/answer can later travel inside this channel, which removes the unauthenticated signalling.
 */
class ControlConnection internal constructor(private val io: FrameIo, private val closeable: Closeable, val session: Session, val remote: String) : Closeable {
    private val sealer = FrameCipher(session.sendKey)
    private val opener = FrameCipher(session.receiveKey)
    private val writeLock = Any()

    @Volatile
    var closed = false
        private set

    fun send(message: ControlMessage) {
        val plain = ControlProtocol.encode(message)
        require(plain.size <= MAX_MESSAGE_BYTES) { "control message too large: ${plain.size} bytes" }
        synchronized(writeLock) { io.write(sealer.seal(plain)) }
    }

    /** The next message; null when the peer closed the connection. Throws [IOException] on a broken or forged frame. */
    fun receive(): Decoded? {
        val frame = io.read(MAX_MESSAGE_BYTES + TAG_BYTES) ?: return null
        val plain = opener.open(frame)
        return try {
            ControlProtocol.decode(plain)
        } catch (e: IllegalArgumentException) {
            throw IOException("malformed control message", e)
        }
    }

    override fun close() {
        closed = true
        runCatching { closeable.close() }
    }

    override fun toString(): String = "ControlConnection($remote, $session)"

    companion object {
        const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val TAG_BYTES = 16

        /** Runs the client handshake over [input]/[output]. Throws [HandshakeException] or [IOException]. */
        fun client(input: InputStream, output: OutputStream, closeable: Closeable, remote: String, handshake: ClientHandshake): ControlConnection {
            val io = FrameIo(input, output)
            io.write(HandshakeCodec.encode(handshake.hello()))
            val reply = io.read(HandshakeCodec.MAX_BYTES) ?: throw IOException("closed during handshake")
            val (finish, session) = handshake.onServerMessage(HandshakeCodec.decode(reply))
            io.write(HandshakeCodec.encode(finish))
            return ControlConnection(io, closeable, session, remote)
        }

        /** Runs the server handshake. On failure a [HandshakeMessage.Reject] is sent when possible, then it throws. */
        fun server(input: InputStream, output: OutputStream, closeable: Closeable, remote: String, handshake: ServerHandshake): ControlConnection {
            val io = FrameIo(input, output)
            val hello = io.read(HandshakeCodec.MAX_BYTES) ?: throw IOException("closed during handshake")
            val reply = try {
                handshake.onClientHello(HandshakeCodec.decode(hello))
            } catch (e: HandshakeException) {
                HandshakeMessage.Reject(e.code)
            }
            io.write(HandshakeCodec.encode(reply))
            if (reply is HandshakeMessage.Reject) throw HandshakeException(reply.code, "rejected client: ${reply.code}")
            val finish = io.read(HandshakeCodec.MAX_BYTES) ?: throw IOException("closed during handshake")
            val session = try {
                handshake.onClientFinish(HandshakeCodec.decode(finish))
            } catch (e: HandshakeException) {
                runCatching { io.write(HandshakeCodec.encode(HandshakeMessage.Reject(e.code))) }
                throw e
            }
            return ControlConnection(io, closeable, session, remote)
        }
    }
}

/** TCP plumbing for [ControlConnection]. */
object ControlSockets {
    const val CONNECT_TIMEOUT_MS = 3_000
    const val HANDSHAKE_TIMEOUT_MS = 5_000

    /** Dials [host]:[port] and runs the client handshake; the socket closes on any failure. */
    suspend fun connect(host: String, port: Int, handshake: ClientHandshake, timeoutMs: Int = CONNECT_TIMEOUT_MS): ControlConnection =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                ControlConnection.client(socket.getInputStream(), socket.getOutputStream(), socket, "$host:$port", handshake)
                    .also { socket.soTimeout = 0 }
            } catch (e: IOException) {
                socket.close()
                throw e
            } catch (e: HandshakeException) {
                socket.close()
                throw e
            }
        }

    /**
     * Listens on [port] until cancelled; every client that completes the handshake is handed to [onSession] (on an IO
     * thread; it must return quickly and keep the connection). Failed handshakes are reported to [onRejected].
     */
    suspend fun serve(
        port: Int,
        handshakeFactory: () -> ServerHandshake,
        onSession: (ControlConnection) -> Unit,
        onRejected: (remote: String, error: Exception) -> Unit = { _, _ -> },
        onBound: (localPort: Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            server.soTimeout = ACCEPT_POLL_MS
            onBound(server.localPort)
            while (true) {
                coroutineContext.ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val remote = socket.remoteSocketAddress.toString().removePrefix("/")
                try {
                    socket.tcpNoDelay = true
                    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                    val connection = ControlConnection.server(socket.getInputStream(), socket.getOutputStream(), socket, remote, handshakeFactory())
                    socket.soTimeout = 0
                    onSession(connection)
                } catch (e: IOException) {
                    socket.close()
                    onRejected(remote, e)
                } catch (e: HandshakeException) {
                    socket.close()
                    onRejected(remote, e)
                }
            }
        }
    }

    private const val ACCEPT_POLL_MS = 500
}
