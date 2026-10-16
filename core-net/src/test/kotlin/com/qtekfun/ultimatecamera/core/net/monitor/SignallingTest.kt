package com.qtekfun.ultimatecamera.core.net.monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignallingTest {
    private val sdp = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\na=group:BUNDLE 0 1\r\n"

    @Test
    fun everyMessageRoundTripsOnOneLine() {
        val messages = listOf(
            SignallingMessage.Hello(SignallingCodec.PROTOCOL_VERSION, MonitorRole.CAMERA),
            SignallingMessage.Offer(sdp),
            SignallingMessage.Answer(sdp),
            SignallingMessage.Ice("0", 0, "candidate:1 1 udp 2122260223 192.168.1.20 40000 typ host"),
            SignallingMessage.Ice(null, 1, "candidate:2 1 udp 2122260223 abcd.local 40001 typ host"),
            SignallingMessage.Bye("done"),
            SignallingMessage.Bye()
        )
        for (message in messages) {
            val line = SignallingCodec.encode(message)
            assertFalse("no raw newline in $line", line.contains('\n') || line.contains('\r'))
            assertEquals(message, SignallingCodec.decode(line))
        }
    }

    @Test
    fun wireFormatIsTypedJson() {
        assertEquals(
            """{"type":"hello","protocol":1,"role":"MONITOR"}""",
            SignallingCodec.encode(SignallingMessage.Hello(1, MonitorRole.MONITOR))
        )
        // Unknown fields from a newer peer are ignored.
        assertEquals(SignallingMessage.Answer("x"), SignallingCodec.decode("""{"type":"answer","sdp":"x","extra":true}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTypeIsRejected() {
        SignallingCodec.decode("""{"type":"teleport","sdp":"x"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageIsRejected() {
        SignallingCodec.decode("not json")
    }

    @Test
    fun channelCarriesMessagesOverLoopbackTcp() = runBlocking {
        val port = 47_898
        val server = async(Dispatchers.IO) { SignallingChannel.accept(port) }
        // The server may not be listening yet: retry for up to a second.
        var client: SignallingChannel? = null
        var attempts = 0
        while (client == null && attempts++ < 50) {
            client = runCatching { SignallingChannel.connect("127.0.0.1", port, timeoutMs = 1_000) }.getOrNull()
            if (client == null) Thread.sleep(20)
        }
        val camera = withTimeout(5_000) { server.await() }
        val monitor = checkNotNull(client)
        try {
            withContext(Dispatchers.IO) {
                monitor.send(SignallingMessage.Hello(1, MonitorRole.MONITOR))
                camera.send(SignallingMessage.Offer(sdp))
                assertEquals(SignallingMessage.Hello(1, MonitorRole.MONITOR), camera.receive())
                assertEquals(SignallingMessage.Offer(sdp), monitor.receive())
                assertTrue(camera.remote.startsWith("127.0.0.1:"))
                monitor.close()
                assertNull("end of stream after the peer closed", camera.receive())
            }
        } finally {
            camera.close()
            monitor.close()
        }
    }
}
