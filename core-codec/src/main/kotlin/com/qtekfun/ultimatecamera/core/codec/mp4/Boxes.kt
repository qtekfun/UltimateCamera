package com.qtekfun.ultimatecamera.core.codec.mp4

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/** The file is not what the post-processor expects; nothing has been written when this is thrown. */
class UnexpectedFileException(message: String) : IOException(message)

/** One ISO-BMFF box header found in the file: [size] includes the [headerSize] bytes of the header. */
class BoxHeader internal constructor(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
    val end: Long get() = offset + size
    val payloadOffset: Long get() = offset + headerSize

    override fun toString(): String = "$type@$offset+$size"
}

/** Reading box headers and small boxes; large leaves are never read, only copied by range. */
internal object BoxReader {
    private const val HEADER = 8
    private const val LARGE_HEADER = 16

    /** Boxes the post-processor reads into memory (headers, track and media headers, sample entries) stay below this. */
    const val MAX_SMALL_BOX = 1L shl 20

    fun header(file: SeekableFile, offset: Long, limit: Long): BoxHeader {
        if (limit - offset < HEADER) unexpected("truncated box header at $offset")
        val b = ByteArray(LARGE_HEADER)
        file.read(offset, b, 0, HEADER)
        val type = Bytes.fourcc(b, 4)
        var size = Bytes.u32(b, 0)
        var header = HEADER
        when (size) {
            1L -> {
                if (limit - offset < LARGE_HEADER) unexpected("truncated large box header at $offset")
                file.read(offset + HEADER, b, HEADER, HEADER)
                size = Bytes.u64(b, HEADER)
                header = LARGE_HEADER
            }
            0L -> size = limit - offset
        }
        if (size < header || size > limit - offset) unexpected("box $type at $offset has size $size beyond ${limit - offset}")
        return BoxHeader(type, offset, header, size)
    }

    /** The boxes between [from] and [to], which must tile the range exactly. */
    fun children(file: SeekableFile, from: Long, to: Long): List<BoxHeader> = buildList {
        var at = from
        while (at < to) {
            val h = header(file, at, to)
            add(h)
            at = h.end
        }
    }

    fun children(file: SeekableFile, box: BoxHeader, prefix: Int = 0): List<BoxHeader> = children(file, box.payloadOffset + prefix, box.end)

    /** The whole box, header included. */
    fun bytes(file: SeekableFile, box: BoxHeader): ByteArray {
        if (box.size > MAX_SMALL_BOX) unexpected("box ${box.type} is too large to read (${box.size})")
        return ByteArray(box.size.toInt()).also { file.read(box.offset, it) }
    }

    fun unexpected(message: String): Nothing = throw UnexpectedFileException(message)
}

/** Big-endian field access on box bytes. */
internal object Bytes {
    private const val BYTE = 0xFF
    private const val BITS = 8

    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and BYTE

    fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl BITS) or u8(b, at + 1)

    fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl BITS) or u8(b, at + i).toLong() }

    fun u64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { acc, i -> (acc shl BITS) or u8(b, at + i).toLong() }

    fun fourcc(b: ByteArray, at: Int): String = String(b, at, 4, Charsets.ISO_8859_1)

    fun putU32(b: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) b[at + i] = (value ushr (BITS * (3 - i))).toByte()
    }

    fun putU64(b: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) b[at + i] = (value ushr (BITS * (7 - i))).toByte()
    }

    fun fourccBytes(type: String): ByteArray = type.toByteArray(Charsets.ISO_8859_1).also { require(it.size == 4) { "bad fourcc $type" } }
}

/** A part of the box tree to write: copied from the file, generated, or a container of both. */
internal sealed interface Node {
    val size: Long
}

/** Bytes [from] until [from] + [size] of the source file, copied in chunks. */
internal class CopyNode(val from: Long, override val size: Long) : Node {
    constructor(box: BoxHeader) : this(box.offset, box.size)
}

/** A complete generated box. */
internal class BytesNode(val data: ByteArray) : Node {
    override val size: Long get() = data.size.toLong()
}

/** A box of [type] whose payload is [prefix] (version, flags, fixed fields) followed by [children]. */
internal class ContainerNode(val type: String, val prefix: ByteArray, val children: List<Node>) : Node {
    override val size: Long = HEADER + prefix.size + children.sumOf { it.size }

    private companion object {
        const val HEADER = 8L
    }
}

/** Builds one small box in memory. */
internal fun box(type: String, body: DataOutputStream.() -> Unit = {}): ByteArray {
    val payload = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
    val out = ByteArrayOutputStream(payload.size + 8)
    DataOutputStream(out).run {
        writeInt(payload.size + 8)
        write(Bytes.fourccBytes(type))
        write(payload)
    }
    return out.toByteArray()
}

internal fun DataOutputStream.fourcc(type: String) = write(Bytes.fourccBytes(type))

/** Writes nodes sequentially from [start], copying [CopyNode] ranges from [source] through one bounded buffer. */
internal class BoxWriter(private val target: SeekableFile, private val source: SeekableFile, start: Long) {
    var position: Long = start
        private set
    private val buffer = ByteArray(COPY_BUFFER)

    fun write(node: Node) {
        when (node) {
            is BytesNode -> put(node.data)
            is CopyNode -> copy(node.from, node.size)
            is ContainerNode -> {
                if (node.size > MAX_U32) BoxReader.unexpected("${node.type} would be larger than 4 GiB")
                val header = ByteArray(8)
                Bytes.putU32(header, 0, node.size)
                Bytes.fourccBytes(node.type).copyInto(header, 4)
                put(header)
                put(node.prefix)
                node.children.forEach(::write)
            }
        }
    }

    private fun put(bytes: ByteArray) {
        target.write(position, bytes)
        position += bytes.size
    }

    private fun copy(from: Long, size: Long) {
        var done = 0L
        while (done < size) {
            val n = minOf(buffer.size.toLong(), size - done).toInt()
            source.read(from + done, buffer, 0, n)
            target.write(position, buffer, 0, n)
            position += n
            done += n
        }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val MAX_U32 = 0xFFFF_FFFFL
    }
}
