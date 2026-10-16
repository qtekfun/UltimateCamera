package com.qtekfun.ultimatecamera.core.net.monitor

import java.math.BigInteger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsParserTest {
    private val entries = listOf(
        StatsEntry("C1", "codec", mapOf("mimeType" to "video/H264")),
        StatsEntry(
            "OT1",
            "outbound-rtp",
            mapOf(
                "kind" to "video",
                "codecId" to "C1",
                "encoderImplementation" to "c2.qti.avc.encoder",
                "framesEncoded" to 300L,
                "framesSent" to 299L,
                "totalEncodeTime" to 1.5,
                "targetBitrate" to 2_000_000.0,
                "qualityLimitationReason" to "none",
                "retransmittedPacketsSent" to BigInteger.valueOf(4)
            )
        ),
        StatsEntry(
            "IT1",
            "inbound-rtp",
            mapOf(
                "kind" to "video",
                "codecId" to "C1",
                "decoderImplementation" to "c2.exynos.h264.decoder",
                "framesDecoded" to 200L,
                "framesDropped" to 1L,
                "jitterBufferDelay" to 3.0,
                "jitterBufferEmittedCount" to BigInteger.valueOf(200),
                "totalDecodeTime" to 0.8,
                "packetsLost" to 2
            )
        ),
        StatsEntry("IA1", "inbound-rtp", mapOf("kind" to "audio")),
        StatsEntry("T1", "transport", mapOf("selectedCandidatePairId" to "CP1")),
        StatsEntry("CP1", "candidate-pair", mapOf("localCandidateId" to "L1", "remoteCandidateId" to "R1", "currentRoundTripTime" to 0.004)),
        StatsEntry(
            "L1",
            "local-candidate",
            mapOf(
                "candidateType" to "host",
                "address" to "192.168.43.1",
                "port" to 40000,
                "networkType" to "wifi",
                "protocol" to "udp"
            )
        ),
        StatsEntry("R1", "remote-candidate", mapOf("candidateType" to "host", "address" to "192.168.43.20", "port" to 50000))
    )

    @Test
    fun outboundVideoIsSummarised() {
        val out = checkNotNull(StatsParser.outboundVideo(entries))
        assertEquals("video/H264", out.codec)
        assertEquals("c2.qti.avc.encoder", out.encoderImplementation)
        assertEquals(300L, out.framesEncoded)
        assertEquals(299L, out.framesSent)
        assertEquals(5.0, checkNotNull(out.avgEncodeMs), 1e-9)
        assertEquals("none", out.qualityLimitationReason)
        assertEquals(4L, out.retransmittedPackets)
    }

    @Test
    fun inboundVideoIsSummarised() {
        val inbound = checkNotNull(StatsParser.inboundVideo(entries))
        assertEquals("c2.exynos.h264.decoder", inbound.decoderImplementation)
        assertEquals(200L, inbound.framesDecoded)
        assertEquals(1L, inbound.framesDropped)
        assertEquals(15.0, checkNotNull(inbound.avgJitterBufferMs), 1e-9)
        assertEquals(4.0, checkNotNull(inbound.avgDecodeMs), 1e-9)
        assertEquals(2L, inbound.packetsLost)
    }

    @Test
    fun selectedPairNamesTheInterface() {
        val pair = checkNotNull(StatsParser.selectedPair(entries))
        assertEquals("192.168.43.1:40000", pair.localAddress)
        assertEquals("192.168.43.20:50000", pair.remoteAddress)
        assertEquals("wifi", pair.localNetworkType)
        assertEquals(4.0, checkNotNull(pair.currentRttMs), 1e-9)
    }

    @Test
    fun missingStatsGiveNull() {
        assertNull(StatsParser.outboundVideo(emptyList()))
        assertNull(StatsParser.inboundVideo(emptyList()))
        assertNull(StatsParser.selectedPair(emptyList()))
    }

    @Test
    fun resultSerialises() {
        val result = MonitorLinkResult(
            role = MonitorRole.CAMERA,
            peer = "192.168.1.30:50000",
            video = MonitorVideoConfig(),
            network = MonitorNetworkOptions(),
            fieldTrials = "",
            latency = MonitorLatencyTracker().snapshot(),
            outbound = StatsParser.outboundVideo(entries)
        )
        val json = Json { allowSpecialFloatingPointValues = true }
        assertEquals(result, json.decodeFromString(MonitorLinkResult.serializer(), json.encodeToString(MonitorLinkResult.serializer(), result)))
    }
}
