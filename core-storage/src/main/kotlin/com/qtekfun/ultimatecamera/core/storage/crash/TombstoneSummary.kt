package com.qtekfun.ultimatecamera.core.storage.crash

/**
 * A readable summary of a native crash tombstone, as `ApplicationExitInfo.getTraceInputStream()` returns it for
 * `REASON_CRASH_NATIVE` (the `Tombstone` protobuf of AOSP `debuggerd/proto/tombstone.proto`). A native crash never
 * reaches the Java crash handler (e.g. libwebrtc aborting on a pending Java exception), so this is how it reaches the
 * owner. Only the signal, the abort message and the crashing thread's backtrace are read; registers, memory and the
 * command line are skipped. A minimal protobuf reader (no dependency); unknown or malformed data gives null fields.
 */
data class TombstoneSummary(val signal: String?, val abortMessage: String?, val threadName: String?, val frames: List<String>) {
    fun format(): String = buildString {
        signal?.let { append("signal: ").append(it).append('\n') }
        abortMessage?.let { append("abort: ").append(it).append('\n') }
        threadName?.let { append("thread: ").append(it).append('\n') }
        frames.forEachIndexed { i, frame -> append("  #").append(i.toString().padStart(2, '0')).append(' ').append(frame).append('\n') }
    }

    companion object {
        const val MAX_FRAMES = 40

        // Field numbers of tombstone.proto.
        private const val TOMBSTONE_TID = 6
        private const val TOMBSTONE_SIGNAL = 10
        private const val TOMBSTONE_ABORT = 14
        private const val TOMBSTONE_THREADS = 16
        private const val SIGNAL_NAME = 2
        private const val SIGNAL_CODE_NAME = 4
        private const val MAP_KEY = 1
        private const val MAP_VALUE = 2
        private const val THREAD_NAME = 2
        private const val THREAD_BACKTRACE = 4
        private const val FRAME_REL_PC = 1
        private const val FRAME_FUNCTION = 4
        private const val FRAME_OFFSET = 5
        private const val FRAME_FILE = 6

        /** Null when [bytes] is not a tombstone this reader understands. */
        @Suppress("TooGenericExceptionCaught")
        fun parse(bytes: ByteArray): TombstoneSummary? = try {
            val top = ProtoReader.fields(bytes)
            val tid = top.firstOrNull { it.number == TOMBSTONE_TID }?.varint
            val signal = top.firstOrNull { it.number == TOMBSTONE_SIGNAL }?.bytes?.let { s ->
                val fields = ProtoReader.fields(s)
                listOfNotNull(fields.string(SIGNAL_NAME), fields.string(SIGNAL_CODE_NAME)).joinToString(" ").ifEmpty { null }
            }
            val abort = top.string(TOMBSTONE_ABORT)
            val crashing = top.filter { it.number == TOMBSTONE_THREADS }.mapNotNull { it.bytes }.map(ProtoReader::fields)
                .firstOrNull { entry -> tid == null || entry.firstOrNull { it.number == MAP_KEY }?.varint == tid }
                ?.firstOrNull { it.number == MAP_VALUE }?.bytes?.let(ProtoReader::fields)
            val frames = crashing.orEmpty().filter { it.number == THREAD_BACKTRACE }.mapNotNull { it.bytes }.take(MAX_FRAMES).map { frame ->
                val f = ProtoReader.fields(frame)
                val pc = f.firstOrNull { it.number == FRAME_REL_PC }?.varint ?: 0L
                val function = f.string(FRAME_FUNCTION)?.takeIf { it.isNotEmpty() }
                val offset = f.firstOrNull { it.number == FRAME_OFFSET }?.varint ?: 0L
                buildString {
                    append("pc ").append(java.lang.Long.toHexString(pc).padStart(PC_DIGITS, '0'))
                    append(' ').append(f.string(FRAME_FILE) ?: "?")
                    function?.let { append(" (").append(it).append('+').append(offset).append(')') }
                }
            }
            if (signal == null && abort == null && frames.isEmpty()) null else TombstoneSummary(signal, abort, crashing?.string(THREAD_NAME), frames)
        } catch (_: RuntimeException) {
            null
        }

        private const val PC_DIGITS = 16
    }
}

/** Just enough protobuf wire format to walk a message: varints, fixed 32/64 and length-delimited fields. */
internal object ProtoReader {
    class Field(val number: Int, val varint: Long?, val bytes: ByteArray?)

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_BYTES = 2
    private const val WIRE_FIXED32 = 5
    private const val MAX_VARINT_BYTES = 10

    /** The fields of one message, in order. Throws [IllegalArgumentException] on malformed data. */
    fun fields(data: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            repeat(MAX_VARINT_BYTES) {
                require(pos < data.size) { "truncated varint" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("varint too long")
        }
        while (pos < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 0x7).toInt()) {
                WIRE_VARINT -> out += Field(number, varint(), null)
                WIRE_FIXED64 -> pos = skip(data, pos, 8)
                WIRE_BYTES -> {
                    val length = varint()
                    require(length >= 0 && length <= data.size - pos) { "bad length" }
                    out += Field(number, null, data.copyOfRange(pos, pos + length.toInt()))
                    pos += length.toInt()
                }
                WIRE_FIXED32 -> pos = skip(data, pos, 4)
                else -> throw IllegalArgumentException("unsupported wire type")
            }
        }
        return out
    }

    private fun skip(data: ByteArray, pos: Int, count: Int): Int {
        require(pos + count <= data.size) { "truncated field" }
        return pos + count
    }
}

private fun List<ProtoReader.Field>.string(number: Int): String? = firstOrNull { it.number == number }?.bytes?.toString(Charsets.UTF_8)
