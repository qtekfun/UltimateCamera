package com.qtekfun.ultimatecamera.core.net

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {
    @Test
    fun percentilesUseNearestRank() {
        val stats = LatencyStats.of(sent = 100, rttMs = (1..100).map { it.toDouble() })
        assertEquals(50.0, stats.p50Ms, 0.0)
        assertEquals(95.0, stats.p95Ms, 0.0)
        assertEquals(99.0, stats.p99Ms, 0.0)
        assertEquals(0.0, stats.lossPercent, 0.0)
    }

    @Test
    fun lossIsCounted() {
        val stats = LatencyStats.of(sent = 10, rttMs = listOf(3.0, 1.0))
        assertEquals(80.0, stats.lossPercent, 1e-9)
        assertEquals(1.0, stats.minMs, 0.0)
        assertEquals(3.0, stats.maxMs, 0.0)
    }

    @Test
    fun probeMeasuresALoopbackEchoServer() = runBlocking {
        val port = 47_899
        val server = launch { UdpLatencyProbe.serve(port) }
        try {
            val result = UdpLatencyProbe.measure("127.0.0.1", port, count = 50, rateHz = 200)
            assertEquals(null, result.error)
            assertTrue("received ${result.stats.received}", result.stats.received >= 45)
            assertTrue(result.stats.p50Ms < 50.0)
        } finally {
            server.cancel()
        }
    }
}
