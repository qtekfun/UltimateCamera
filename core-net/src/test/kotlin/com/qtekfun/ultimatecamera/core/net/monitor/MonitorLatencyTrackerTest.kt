package com.qtekfun.ultimatecamera.core.net.monitor

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorLatencyTrackerTest {
    private val ms = 1_000_000L

    @Test
    fun latencyIsEchoTimeMinusHalfTheRtt() {
        val tracker = MonitorLatencyTracker()
        tracker.pingSent()
        tracker.onPong(sentNanos = 0, nowNanos = 20 * ms)
        tracker.frameSubmitted(counter = 7, nanoTime = 1_000 * ms)
        assertTrue(tracker.onEcho(counter = 7, nowNanos = 1_090 * ms))
        val result = tracker.snapshot()
        assertEquals(80.0, result.glassToGlass.p50Ms, 1e-9)
        assertEquals(20.0, result.controlRtt.p50Ms, 1e-9)
        assertEquals(1, result.framesSubmitted)
        assertEquals(1, result.echoesMatched)
        assertEquals(0.0, result.glassToGlass.lossPercent, 0.0)
    }

    @Test
    fun rttIsSmoothed() {
        val tracker = MonitorLatencyTracker()
        tracker.onPong(0, 8 * ms)
        tracker.onPong(0, 16 * ms)
        // 8 + (16 - 8) / 8 = 9 ms.
        assertEquals(9.0, tracker.smoothedRttMs, 1e-9)
    }

    @Test
    fun echoesBeforeTheFirstRttCarryNoValue() {
        val tracker = MonitorLatencyTracker()
        tracker.frameSubmitted(1, 0)
        assertTrue(tracker.onEcho(1, 50 * ms))
        val result = tracker.snapshot()
        assertEquals(1, result.echoesWithoutRtt)
        assertEquals(0, result.glassToGlass.received)
        assertTrue(result.glassToGlass.p50Ms.isNaN())
    }

    @Test
    fun duplicateUnknownAndOverwrittenEchoesDoNotMatch() {
        val tracker = MonitorLatencyTracker(capacity = 4)
        tracker.onPong(0, 2 * ms)
        tracker.frameSubmitted(1, 0)
        assertTrue(tracker.onEcho(1, 10 * ms))
        assertFalse("duplicate", tracker.onEcho(1, 11 * ms))
        assertFalse("never submitted", tracker.onEcho(99, 11 * ms))
        tracker.frameSubmitted(2, 0)
        tracker.frameSubmitted(6, 0) // Same slot as 2 in a ring of 4.
        assertFalse("overwritten", tracker.onEcho(2, 12 * ms))
        assertTrue(tracker.onEcho(6, 12 * ms))
        val result = tracker.snapshot()
        assertEquals(5, result.echoesReceived)
        assertEquals(2, result.echoesMatched)
        assertEquals(3, result.framesSubmitted)
        assertEquals(100.0 / 3, result.glassToGlass.lossPercent, 1e-9)
    }

    @Test
    fun negativeCountersUseTheRingToo() {
        val tracker = MonitorLatencyTracker(capacity = 8)
        tracker.onPong(0, 0)
        tracker.frameSubmitted(-3, 0)
        assertTrue(tracker.onEcho(-3, 5 * ms))
    }

    @Test
    fun resetForgetsFramesButKeepsTheRtt() {
        val tracker = MonitorLatencyTracker()
        tracker.onPong(0, 10 * ms)
        tracker.frameSubmitted(1, 0)
        tracker.reset()
        assertFalse(tracker.onEcho(1, 20 * ms))
        val result = tracker.snapshot()
        assertEquals(0, result.framesSubmitted)
        assertEquals(10.0, tracker.smoothedRttMs, 1e-9)
    }

    @Test
    fun samplesBeyondTheBufferAreCountedButNotStored() {
        val tracker = MonitorLatencyTracker(capacity = 16, maxSamples = 2)
        tracker.onPong(0, 0)
        repeat(3) {
            tracker.frameSubmitted(it, 0)
            tracker.onEcho(it, ms)
        }
        val result = tracker.snapshot()
        assertEquals(3, result.echoesMatched)
        assertEquals(2, result.glassToGlass.received)
    }

    @Test
    fun linkMessagesRoundTrip() {
        for (message in listOf(LinkMessage.Echo(123_456), LinkMessage.Echo(-1), LinkMessage.Ping(Long.MAX_VALUE), LinkMessage.Pong(42L))) {
            assertEquals(message, LinkMessage.decode(ByteBuffer.wrap(message.encode())))
        }
        assertNull(LinkMessage.decode(ByteBuffer.wrap(byteArrayOf(9, 0, 0, 0, 0))))
        assertNull("short", LinkMessage.decode(ByteBuffer.wrap(byteArrayOf(1, 0))))
        assertNull("empty", LinkMessage.decode(ByteBuffer.allocate(0)))
    }

    @Test
    fun stampRowIsTheCentreOfTheTopFourPercent() {
        assertEquals(14, StampStrip.row(720))
        assertEquals(22, StampStrip.row(1080))
        assertEquals(43, StampStrip.row(2160))
        assertEquals(42, StampStrip.bandTop(2160))
        assertEquals(14, StampStrip.bandTop(720))
        assertEquals(0, StampStrip.bandTop(2))
        for (height in 2..2200) {
            val top = StampStrip.bandTop(height)
            assertTrue(top % 2 == 0 && StampStrip.row(height) in top until top + StampStrip.BAND_ROWS && top + StampStrip.BAND_ROWS <= height)
        }
    }

    @Test
    fun fieldTrialsDependOnRoleAndOption() {
        assertEquals("", PeerSession.fieldTrialsFor(MonitorRole.MONITOR, MonitorNetworkOptions(zeroPlayoutDelay = false)))
        assertTrue(PeerSession.fieldTrialsFor(MonitorRole.CAMERA, MonitorNetworkOptions()).startsWith("WebRTC-ForceSendPlayoutDelay/"))
        assertTrue(PeerSession.fieldTrialsFor(MonitorRole.MONITOR, MonitorNetworkOptions()).contains("WebRTC-ZeroPlayoutDelay/"))
    }
}
