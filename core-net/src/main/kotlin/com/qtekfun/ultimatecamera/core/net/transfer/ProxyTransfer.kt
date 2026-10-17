package com.qtekfun.ultimatecamera.core.net.transfer

import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.FrameCipher
import com.qtekfun.ultimatecamera.core.net.control.FrameIo
import com.qtekfun.ultimatecamera.core.net.control.HandshakeCodec
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMessage
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.control.Session
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the camera offers for download (REQ-NET-006); implemented by the app over its recorded proxies. */
interface ProxySource {
    fun list(): List<ProxyEntry>

    /** Opens [name] for reading from its start, or null when it is not offered. */
    @Throws(IOException::class)
    fun open(name: String): InputStream?

    /** True while the camera records: transfers pause so they never compete with a take for storage or radio. */
    val busy: Boolean
}

/** One authenticated, encrypted proxy-transfer socket (same handshake and framing as the control channel). */
internal class SecureChannel(private val io: FrameIo, private val closeable: Closeable, val session: Session) : Closeable {
    private val sealer = FrameCipher(session.sendKey)
    private val opener = FrameCipher(session.receiveKey)

    fun send(plain: ByteArray) = io.write(sealer.seal(plain))

    /** The next frame's plaintext, or null at a clean end of stream. */
    fun receive(): ByteArray? = io.read(ProxyTransferProtocol.MAX_FRAME_BYTES + TAG_BYTES)?.let(opener::open)

    override fun close() {
        runCatching { closeable.close() }
    }

    companion object {
        private const val TAG_BYTES = 16

        fun client(input: InputStream, output: OutputStream, closeable: Closeable, handshake: ClientHandshake): SecureChannel {
            val io = FrameIo(input, output)
            io.write(HandshakeCodec.encode(handshake.hello()))
            val reply = io.read(HandshakeCodec.MAX_BYTES) ?: throw IOException("closed during handshake")
            val (finish, session) = handshake.onServerMessage(HandshakeCodec.decode(reply))
            io.write(HandshakeCodec.encode(finish))
            return SecureChannel(io, closeable, session)
        }

        fun server(input: InputStream, output: OutputStream, closeable: Closeable, handshake: ServerHandshake): SecureChannel {
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
            return SecureChannel(io, closeable, handshake.onClientFinish(HandshakeCodec.decode(finish)))
        }
    }
}

/**
 * The camera side of the proxy upload (REQ-NET-006, D59): lists [source]'s proxies and streams them in chunks to paired
 * controllers. Each client is served on its own minimum-priority thread; while [ProxySource.busy] (recording) a
 * transfer stops with [TransferReply.Paused] and the controller resumes it later from the bytes it holds. The SHA-256 of
 * the whole file follows the last chunk.
 */
class ProxyTransferServer(private val source: ProxySource) {
    /** Listens on [port] until cancelled; only devices that pass the pairing handshake are served. */
    suspend fun serve(port: Int, handshakeFactory: () -> ServerHandshake, onBound: (localPort: Int) -> Unit = {}) = withContext(Dispatchers.IO) {
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
                Thread({ handleSocket(socket, handshakeFactory()) }, "uc-proxy-upload").apply {
                    priority = Thread.MIN_PRIORITY
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun handleSocket(socket: Socket, handshake: ServerHandshake) {
        socket.use {
            try {
                socket.soTimeout = ControlSockets.HANDSHAKE_TIMEOUT_MS
                val channel = SecureChannel.server(socket.getInputStream(), socket.getOutputStream(), socket, handshake)
                socket.soTimeout = 0
                serveChannel(channel)
            } catch (_: IOException) {
                // A dropped or refused client: it reconnects and resumes on its own.
            } catch (_: HandshakeException) {
                // Not paired: nothing to serve.
            }
        }
    }

    internal fun serveChannel(channel: SecureChannel) {
        while (true) {
            val frame = channel.receive() ?: return
            when (val request = ProxyTransferProtocol.decodeRequest(frame)) {
                TransferRequest.List -> channel.send(ProxyTransferProtocol.encode(TransferReply.Listing(source.list())))
                is TransferRequest.Get -> send(channel, request)
            }
        }
    }

    private fun send(channel: SecureChannel, request: TransferRequest.Get) {
        val entry = source.list().firstOrNull { it.name == request.name }
        val failure = when {
            entry == null -> "unknown proxy ${request.name}"
            request.offset !in 0..entry.bytes -> "bad offset ${request.offset}"
            else -> null
        }
        val input = if (failure == null) source.open(request.name) else null
        if (input == null) {
            channel.send(ProxyTransferProtocol.encode(TransferReply.Failed(failure ?: "cannot open ${request.name}")))
            return
        }
        input.use { stream(channel, it, request.offset) }
    }

    private fun stream(channel: SecureChannel, input: InputStream, offset: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ProxyTransferProtocol.CHUNK_BYTES)
        // The hash covers the whole file, so the bytes the client already holds are read (not sent) first.
        var position = 0L
        while (position < offset) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), offset - position).toInt())
            if (n < 0) throw IOException("proxy shorter than offset")
            digest.update(buffer, 0, n)
            position += n
        }
        var n = 0
        while (n >= 0) {
            if (source.busy) {
                channel.send(ProxyTransferProtocol.encode(TransferReply.Paused))
                return
            }
            n = input.read(buffer)
            if (n > 0) {
                digest.update(buffer, 0, n)
                channel.send(ProxyTransferProtocol.encode(TransferReply.Chunk(position, buffer, n)))
                position += n
            }
        }
        channel.send(ProxyTransferProtocol.encode(TransferReply.Done(position, digest.digest())))
    }

    private companion object {
        const val ACCEPT_POLL_MS = 500
    }
}

/** How a download ended. */
sealed interface DownloadResult {
    /** The file is complete and its SHA-256 ([sha256Hex]) matches the camera's. */
    data class Complete(val bytes: Long, val sha256Hex: String) : DownloadResult

    /** The camera is recording; [bytes] are kept and the next download resumes from them. */
    data class Paused(val bytes: Long) : DownloadResult

    /** The hash did not match: the local copy was deleted, download it again. */
    data object Corrupt : DownloadResult

    data class Failed(val reason: String) : DownloadResult
}

/**
 * The controller side of the proxy upload (a monitor or master, REQ-NET-006): list the camera's proxies and download
 * them, resuming from what is already on disk. Blocking: call from an IO thread. Close when done.
 */
class ProxyTransferClient internal constructor(private val channel: SecureChannel) : Closeable {
    fun list(): List<ProxyEntry> {
        channel.send(ProxyTransferProtocol.encode(TransferRequest.List))
        return when (val reply = next()) {
            is TransferReply.Listing -> reply.entries
            is TransferReply.Failed -> throw IOException(reply.reason)
            else -> throw IOException("unexpected reply $reply")
        }
    }

    /**
     * Downloads [name] into [file]; when [file] already holds the first bytes (an interrupted or paused download) only
     * the rest is fetched. [onProgress] gets the bytes held so far after each chunk.
     */
    fun download(name: String, file: File, onProgress: (Long) -> Unit = {}): DownloadResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val held = if (file.exists()) file.length() else 0L
        if (held > 0) FileInputStream(file).use { hashInto(digest, it) }
        channel.send(ProxyTransferProtocol.encode(TransferRequest.Get(name, held)))
        val result = FileOutputStream(file, true).use { out ->
            val receiver = Receiver(out, digest, held, onProgress)
            var outcome: DownloadResult? = null
            while (outcome == null) outcome = receiver.on(next())
            outcome
        }
        if (result == DownloadResult.Corrupt) file.delete()
        return result
    }

    /** Appends chunks to [out]; the outcome once the camera ends, pauses or fails the transfer (null: more to come). */
    private class Receiver(
        private val out: FileOutputStream,
        private val digest: MessageDigest,
        private var held: Long,
        private val onProgress: (Long) -> Unit
    ) {
        fun on(reply: TransferReply): DownloadResult? = when (reply) {
            is TransferReply.Chunk -> chunk(reply)
            is TransferReply.Done -> done(reply)
            TransferReply.Paused -> DownloadResult.Paused(held)
            is TransferReply.Failed -> DownloadResult.Failed(reply.reason)
            is TransferReply.Listing -> DownloadResult.Failed("unexpected listing")
        }

        private fun chunk(reply: TransferReply.Chunk): DownloadResult? {
            if (reply.offset != held) return DownloadResult.Failed("chunk at ${reply.offset}, expected $held")
            out.write(reply.data, 0, reply.length)
            digest.update(reply.data, 0, reply.length)
            held += reply.length
            onProgress(held)
            return null
        }

        private fun done(reply: TransferReply.Done): DownloadResult {
            out.flush()
            out.fd.sync()
            val ours = digest.digest()
            val intact = reply.totalBytes == held && MessageDigest.isEqual(ours, reply.sha256)
            return if (intact) DownloadResult.Complete(held, ProxyTransferProtocol.hex(ours)) else DownloadResult.Corrupt
        }
    }

    private fun hashInto(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(HASH_BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            digest.update(buffer, 0, n)
        }
    }

    private fun next(): TransferReply = ProxyTransferProtocol.decodeReply(channel.receive() ?: throw IOException("camera closed the transfer"))

    override fun close() = channel.close()

    companion object {
        private const val HASH_BUFFER = 64 * 1024

        /** Dials the camera's proxy socket at [host]:[port] and runs the pairing handshake. */
        suspend fun connect(host: String, port: Int, handshake: ClientHandshake): ProxyTransferClient = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), ControlSockets.CONNECT_TIMEOUT_MS)
                socket.soTimeout = ControlSockets.HANDSHAKE_TIMEOUT_MS
                val channel = SecureChannel.client(socket.getInputStream(), socket.getOutputStream(), socket, handshake)
                socket.soTimeout = READ_TIMEOUT_MS
                ProxyTransferClient(channel)
            } catch (e: IOException) {
                socket.close()
                throw e
            } catch (e: HandshakeException) {
                socket.close()
                throw e
            }
        }

        /** A camera that sends nothing for this long (and is not paused) is considered gone. */
        private const val READ_TIMEOUT_MS = 30_000
    }
}
