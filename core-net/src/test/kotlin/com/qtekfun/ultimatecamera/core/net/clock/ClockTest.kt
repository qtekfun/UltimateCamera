package com.qtekfun.ultimatecamera.core.net.clock

import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    private val ms = 1_000_000L

    /**
     * Simulated Wi-Fi: the server clock is the client clock × (1 + skew) + offset; each leg takes 1.5 ms plus a heavy,
     * one-sided random tail (queues, power save: up to 40 ms), asymmetric between the legs.
     */
    private fun simulate(trueOffset: Long, skewPpm: Double, count: Int, intervalNanos: Long, seed: Int = 1): Pair<ClockEstimator, (Long) -> Long> {
        val random = Random(seed)
        val server = { client: Long -> client + trueOffset + (client * skewPpm / 1e6).toLong() }
        val estimator = ClockEstimator()
        var t = 10_000 * ms
        repeat(count) {
            val up = (1.5 * ms + random.nextDouble().let { it * it * it } * 40 * ms).toLong()
            val down = (1.5 * ms + random.nextDouble().let { it * it * it } * 40 * ms).toLong()
            val t0 = t
            val t1 = server(t0 + up)
            val t2 = t1 + 50_000 // 50 µs in the server
            val t3 = t0 + up + 50_000 + down
            estimator.add(ClockSample(t0, t1, t2, t3))
            t += intervalNanos
        }
        return estimator to server
    }

    @Test
    fun minimumDelaySampleGivesSubMillisecondOffset() {
        val trueOffset = 7_345_678_901L
        val (estimator, server) = simulate(trueOffset, 0.0, count = 64, intervalNanos = 50 * ms)
        val e = assertNotNull(estimator.estimate())
        val error = abs(e.toServer(20_000 * ms) - server(20_000 * ms))
        assertTrue("error ${error / 1e3} µs, bound ${e.errorBoundNanos / 1e3} µs", error <= e.errorBoundNanos)
        assertTrue("error ${error / 1e3} µs", error < ms)
        assertTrue(e.minDelayNanos < 5 * ms)
    }

    @Test
    fun skewIsTrackedOverLongSessions() {
        // 40 ppm drift = 2.4 ms per minute: after 10 minutes the uncorrected error would be 24 ms.
        val (estimator, server) = simulate(trueOffset = -3 * 1_000 * ms, skewPpm = 40.0, count = 64, intervalNanos = 2_000 * ms)
        val e = assertNotNull(estimator.estimate())
        assertEquals(40.0, e.skewPpm, 15.0)
        val later = 10_000 * ms + 64 * 2_000 * ms + 600_000 * ms // ten minutes after the last sample
        val error = abs(e.toServer(later) - server(later))
        assertTrue("error ${error / 1e6} ms", error < 10 * ms)
    }

    @Test
    fun conversionsAreInverse() {
        val e = ClockEstimate(offsetNanos = 5_000 * ms, errorBoundNanos = ms, minDelayNanos = 2 * ms, samples = 10, referenceNanos = 1_000 * ms, skewPpm = 37.5)
        for (local in listOf(0L, 999 * ms, 3_600_000 * ms)) assertEquals(local.toDouble(), e.toClient(e.toServer(local)).toDouble(), 2.0)
    }

    @Test
    fun implausibleAndStalledSamplesAreDropped() {
        val estimator = ClockEstimator()
        assertFalse(estimator.add(ClockSample(10, 5, 6, 9))) // t3 < t0
        assertFalse(estimator.add(ClockSample(0, 10, 5, 20))) // t2 < t1
        assertFalse(estimator.add(ClockSample(0, 0, 0, 300 * ms))) // Wi-Fi stall
        assertTrue(estimator.add(ClockSample(0, 0, 0, 2 * ms)))
        assertNull(estimator.estimate()) // fewer than 8 samples
        assertNotNull(estimator.estimate(minSamples = 1))
    }

    @Test
    fun syncedClockFollowsTheEstimator() {
        val estimator = ClockEstimator()
        val view = SyncedClock(estimator, minSamples = 2)
        assertFalse(view.isSynced)
        assertNull(view.toLocal(0))
        estimator.add(ClockSample(100 * ms, 1_100 * ms, 1_100 * ms, 102 * ms))
        estimator.add(ClockSample(200 * ms, 1_201 * ms, 1_201 * ms, 202 * ms))
        assertTrue(view.isSynced)
        assertEquals(999 * ms, view.toCommon(0)) // first of the two equally fast samples
        assertEquals(ms, view.errorBoundNanos)
    }

    @Test
    fun sourceClockIsMonotonicAndAnchoredToTheEpoch() {
        var mono = 42 * ms
        val clock = SourceClock({ mono }, epochNanosAtStart = 1_790_000_000_000 * ms)
        assertEquals(1_790_000_000_000 * ms, clock.now())
        mono += 1_500 * ms
        assertEquals(1_790_000_001_500 * ms, clock.now())
        assertEquals(mono, clock.toLocal(clock.now()))
        assertEquals(0L, clock.errorBoundNanos)
    }

    @Test
    fun packetsAreAuthenticated() {
        val key = Secrets.randomBytes(32)
        val packet = ClockPacket(ClockPacket.REQUEST, ClockPacket.keyId(key), 7, 123, 0, 0)
        val bytes = packet.encode(key)
        assertEquals(ClockPacket.SIZE, bytes.size)
        assertEquals(packet, ClockPacket.decode(bytes, bytes.size, key))
        assertNull(ClockPacket.decode(bytes, bytes.size, Secrets.randomBytes(32)))
        val forged = bytes.copyOf().also { it[30] = (it[30] + 1).toByte() } // shifted t0
        assertNull(ClockPacket.decode(forged, forged.size, key))
        assertNull(ClockPacket.decode(bytes, bytes.size - 1, key))

        val server = ClockServer(SourceClock({ 0L }, 0L))
        assertNull(server.respond(bytes, bytes.size, 5)) // key not registered
        server.addKey(key)
        val reply = assertNotNull(ClockPacket.decode(assertNotNull(server.respond(bytes, bytes.size, 5)), ClockPacket.SIZE, key))
        assertEquals(ClockPacket.RESPONSE, reply.type)
        assertEquals(5L, reply.t1)
        server.removeKey(key)
        assertNull(server.respond(bytes, bytes.size, 5))
    }

    @Test
    fun loopbackUdpSyncFindsTheOffset() = runBlocking {
        withTimeout(20_000) {
            val key = Secrets.randomBytes(32)
            val offset = 5_000 * ms
            val source = SourceClock({ System.nanoTime() }, epochNanosAtStart = System.nanoTime() + offset)
            val server = ClockServer(source).apply { addKey(key) }
            val bound = CompletableDeferred<Int>()
            val serving = launch(Dispatchers.IO) { server.serve(port = 0) { bound.complete(it) } }
            val port = bound.await()
            val estimator = ClockEstimator()
            val client = ClockClient("127.0.0.1", port, key, { System.nanoTime() }, estimator)
            withContext(Dispatchers.IO) {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 300
                    repeat(32) { seq -> client.exchange(socket, InetAddress.getLoopbackAddress(), seq.toLong())?.let { estimator.add(it) } }
                }
            }
            val e = assertNotNull(estimator.estimate())
            // Same machine, same clock: the estimate must equal the configured offset within the bound (loopback ≪ 1 ms).
            assertTrue("offset error ${(e.offsetNanos - offset) / 1e3} µs", abs(e.offsetNanos - offset) <= e.errorBoundNanos + 50_000)
            serving.cancelAndJoin()
        }
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
