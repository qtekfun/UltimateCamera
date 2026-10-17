package com.qtekfun.ultimatecamera.core.storage.crash

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneSummaryTest {
    /** A tiny protobuf writer for building tombstones by hand. */
    private class Proto {
        val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun int(field: Int, value: Long) = apply {
            varint((field shl 3).toLong())
            varint(value)
        }

        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray())

        fun message(field: Int, value: Proto) = bytes(field, value.out.toByteArray())

        fun fixed64(field: Int) = apply {
            varint(((field shl 3) or 1).toLong())
            out.write(ByteArray(8))
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun frame(pc: Long, file: String, function: String? = null, offset: Long = 0): Proto {
        val f = Proto().int(1, pc).int(2, pc + 0x1000)
        if (function != null) f.string(4, function).int(5, offset)
        return f.string(6, file)
    }

    private fun thread(name: String, vararg frames: Proto): Proto {
        val t = Proto().int(1, 1).string(2, name)
        frames.forEach { t.message(4, it) }
        return t
    }

    @Test
    fun readsSignalAbortMessageAndTheCrashingThreadsBacktrace() {
        val tombstone = Proto()
            .int(1, 3) // arch
            .string(2, "oplus/fingerprint")
            .int(5, 1234) // pid
            .int(6, 1301) // tid of the crashing thread
            .string(9, "com.qtekfun.ultimatecamera")
            .message(10, Proto().int(1, 6).string(2, "SIGABRT").int(3, 0).string(4, "SI_TKILL"))
            .string(14, "JNI DETECTED ERROR IN APPLICATION: java_exception java.lang.SecurityException: ACCESS_NETWORK_STATE")
            .fixed64(99)
            .message(16, Proto().int(1, 1234).message(2, thread("main", frame(0x10, "/apex/libc.so"))))
            .message(
                16,
                Proto().int(1, 1301).message(
                    2,
                    thread(
                        "signaling_thread",
                        frame(0x5c1a0, "/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164),
                        frame(0x123456, "/data/app/x/lib/arm64/libjingle_peerconnection_so.so")
                    )
                )
            )
            .toByteArray()
        val summary = TombstoneSummary.parse(tombstone)!!
        assertEquals("SIGABRT SI_TKILL", summary.signal)
        assertEquals("JNI DETECTED ERROR IN APPLICATION: java_exception java.lang.SecurityException: ACCESS_NETWORK_STATE", summary.abortMessage)
        assertEquals("signaling_thread", summary.threadName)
        assertEquals(
            listOf(
                "pc 000000000005c1a0 /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)",
                "pc 0000000000123456 /data/app/x/lib/arm64/libjingle_peerconnection_so.so"
            ),
            summary.frames
        )
        assertTrue(summary.format().contains("  #01 pc 0000000000123456"))
    }

    @Test
    fun garbageAndEmptyInputGiveNull() {
        assertNull(TombstoneSummary.parse(ByteArray(0)))
        assertNull(TombstoneSummary.parse(byteArrayOf(0x0a, 0x7f, 0x01))) // length past the end
        assertNull(TombstoneSummary.parse(byteArrayOf(0x0b))) // unsupported wire type
        assertNull(TombstoneSummary.parse("not a protobuf at all".toByteArray()))
    }

    @Test
    fun framesAreCappedAndMissingTidPicksTheFirstThread() {
        val frames = Array(TombstoneSummary.MAX_FRAMES + 10) { frame(it.toLong(), "lib.so") }
        val tombstone = Proto().string(14, "abort").message(16, Proto().int(1, 7).message(2, thread("only", *frames))).toByteArray()
        val summary = TombstoneSummary.parse(tombstone)!!
        assertEquals(TombstoneSummary.MAX_FRAMES, summary.frames.size)
        assertEquals("only", summary.threadName)
    }

    @Test
    fun fixed32FieldsAreSkippedAndOverlongVarintsRejected() {
        // field 3, wire type 5 (fixed32), then the abort message.
        val withFixed32 = byteArrayOf(0x1d, 1, 2, 3, 4) + Proto().string(14, "a").toByteArray()
        assertEquals("a", TombstoneSummary.parse(withFixed32)!!.abortMessage)
        assertNull(TombstoneSummary.parse(byteArrayOf(0x1d, 1, 2))) // truncated fixed32
        assertNull(TombstoneSummary.parse(ByteArray(11) { 0x80.toByte() })) // varint longer than 10 bytes
        assertNull(TombstoneSummary.parse(byteArrayOf(0x08, 0x80.toByte()))) // truncated varint
    }

    @Test
    fun formatLeavesOutWhatIsMissing() {
        assertEquals("abort: x\n", TombstoneSummary(null, "x", null, emptyList()).format())
        assertEquals("signal: SIGSEGV\nthread: t\n  #00 f\n", TombstoneSummary("SIGSEGV", null, "t", listOf("f")).format())
        // Only a signal with neither name nor code name: nothing usable.
        assertNull(TombstoneSummary.parse(Proto().message(10, Proto().int(1, 11)).toByteArray()))
    }
}
