package com.qtekfun.ultimatecamera.core.net.transfer

import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A proxy the camera offers (REQ-NET-006, D59): its file name and size in bytes. */
@Serializable
data class ProxyEntry(val name: String, val bytes: Long)

/** Controller to camera, on the proxy data socket. */
sealed interface TransferRequest {
    /** The proxies the camera holds. */
    data object List : TransferRequest

    /** Send [name] from byte [offset] on (the bytes the controller already holds: resume). */
    data class Get(val name: String, val offset: Long) : TransferRequest
}

/** Camera to controller, on the proxy data socket. */
sealed interface TransferReply {
    data class Listing(val entries: kotlin.collections.List<ProxyEntry>) : TransferReply

    /** [length] bytes of the file at [offset], in [data] from index 0. */
    class Chunk(val offset: Long, val data: ByteArray, val length: Int) : TransferReply

    /** The file is complete at [totalBytes]; [sha256] is the hash of the whole file (from byte 0). */
    class Done(val totalBytes: Long, val sha256: ByteArray) : TransferReply

    /** The camera started recording: the transfer stops here and is resumed later with a new [TransferRequest.Get]. */
    data object Paused : TransferReply

    data class Failed(val reason: String) : TransferReply
}

/**
 * Wire format of the proxy transfer (D59). It runs on its own TCP socket ([DEFAULT_PORT]), after the same mutual
 * handshake and AES-GCM framing as the control channel, so a bulk download never delays a REC command or a heartbeat.
 * Each frame's plaintext is one kind byte followed by its payload: JSON for the small messages, raw bytes for chunks.
 */
object ProxyTransferProtocol {
    /** 47800 UDP probe, 47801 signalling, 47802 control, 47803 clock; 47804 is the proxy data socket. */
    const val DEFAULT_PORT = 47_804

    /** Chunk payload: big enough for throughput, small enough to pause quickly when a take starts. */
    const val CHUNK_BYTES = 256 * 1024

    /** Largest plaintext frame: a chunk plus its header. */
    const val MAX_FRAME_BYTES = CHUNK_BYTES + 64

    const val SHA256_BYTES = 32

    private const val REQ_LIST: Byte = 1
    private const val REQ_GET: Byte = 2
    private const val REP_LISTING: Byte = 0x11
    private const val REP_CHUNK: Byte = 0x12
    private const val REP_DONE: Byte = 0x13
    private const val REP_PAUSED: Byte = 0x14
    private const val REP_FAILED: Byte = 0x15
    private const val LONG_BYTES = 8

    private val json = Json { ignoreUnknownKeys = true }
    private val listing = ListSerializer(ProxyEntry.serializer())

    @Serializable
    private data class GetBody(val name: String, val offset: Long)

    fun encode(request: TransferRequest): ByteArray = when (request) {
        TransferRequest.List -> byteArrayOf(REQ_LIST)
        is TransferRequest.Get -> byteArrayOf(REQ_GET) + json.encodeToString(GetBody.serializer(), GetBody(request.name, request.offset)).toByteArray()
    }

    fun encode(reply: TransferReply): ByteArray = when (reply) {
        is TransferReply.Listing -> byteArrayOf(REP_LISTING) + json.encodeToString(listing, reply.entries).toByteArray()
        is TransferReply.Chunk -> ByteBuffer.allocate(
            1 + LONG_BYTES + reply.length
        ).put(REP_CHUNK).putLong(reply.offset).put(reply.data, 0, reply.length).array()
        is TransferReply.Done -> ByteBuffer.allocate(1 + LONG_BYTES + SHA256_BYTES).put(REP_DONE).putLong(reply.totalBytes).put(reply.sha256).array()
        TransferReply.Paused -> byteArrayOf(REP_PAUSED)
        is TransferReply.Failed -> byteArrayOf(REP_FAILED) + reply.reason.toByteArray()
    }

    /** Throws [IOException] for a malformed frame. */
    fun decodeRequest(frame: ByteArray): TransferRequest = parse(frame) {
        when (frame[0]) {
            REQ_LIST -> TransferRequest.List
            REQ_GET -> json.decodeFromString(GetBody.serializer(), body(frame)).let { TransferRequest.Get(it.name, it.offset) }
            else -> throw IOException("unknown request kind ${frame[0]}")
        }
    }

    /** Throws [IOException] for a malformed frame. */
    fun decodeReply(frame: ByteArray): TransferReply = parse(frame) {
        when (frame[0]) {
            REP_LISTING -> TransferReply.Listing(json.decodeFromString(listing, body(frame)))
            REP_CHUNK -> ByteBuffer.wrap(frame, 1, frame.size - 1).let { b ->
                val offset = b.long
                TransferReply.Chunk(offset, frame.copyOfRange(1 + LONG_BYTES, frame.size), frame.size - 1 - LONG_BYTES)
            }
            REP_DONE -> {
                if (frame.size != 1 + LONG_BYTES + SHA256_BYTES) throw IOException("bad done frame")
                TransferReply.Done(ByteBuffer.wrap(frame, 1, LONG_BYTES).long, frame.copyOfRange(1 + LONG_BYTES, frame.size))
            }
            REP_PAUSED -> TransferReply.Paused
            REP_FAILED -> TransferReply.Failed(body(frame))
            else -> throw IOException("unknown reply kind ${frame[0]}")
        }
    }

    private fun body(frame: ByteArray): String = String(frame, 1, frame.size - 1, Charsets.UTF_8)

    private inline fun <T> parse(frame: ByteArray, block: () -> T): T {
        if (frame.isEmpty()) throw IOException("empty transfer frame")
        return try {
            block()
        } catch (e: SerializationException) {
            throw IOException("malformed transfer frame", e)
        } catch (e: java.nio.BufferUnderflowException) {
            throw IOException("short transfer frame", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("malformed transfer frame", e)
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
