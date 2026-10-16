package com.qtekfun.ultimatecamera.core.net.monitor

import kotlinx.serialization.Serializable

/** The camera's outgoing video, from libwebrtc's "outbound-rtp" stats. Null fields were not reported. */
@Serializable
data class OutboundVideoStats(
    val codec: String? = null,
    /** For example "c2.qti.avc.encoder" (hardware) or "libvpx" (software). */
    val encoderImplementation: String? = null,
    val powerEfficientEncoder: Boolean? = null,
    val framesEncoded: Long? = null,
    val framesSent: Long? = null,
    val keyFramesEncoded: Long? = null,
    val framesPerSecond: Double? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
    val targetBitrateBps: Double? = null,
    /** "none", "cpu", "bandwidth" or "other". */
    val qualityLimitationReason: String? = null,
    val avgEncodeMs: Double? = null,
    val nackCount: Long? = null,
    val retransmittedPackets: Long? = null
)

/** The monitor's incoming video, from libwebrtc's "inbound-rtp" stats. Null fields were not reported. */
@Serializable
data class InboundVideoStats(
    val codec: String? = null,
    val decoderImplementation: String? = null,
    val powerEfficientDecoder: Boolean? = null,
    val framesReceived: Long? = null,
    val framesDecoded: Long? = null,
    val framesDropped: Long? = null,
    val framesPerSecond: Double? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
    /** jitterBufferDelay / jitterBufferEmittedCount: the average time a frame waited in the jitter buffer. */
    val avgJitterBufferMs: Double? = null,
    val avgDecodeMs: Double? = null,
    val freezeCount: Long? = null,
    val packetsLost: Long? = null,
    val nackCount: Long? = null
)

/** The ICE candidate pair in use: shows which interface (Wi-Fi, hotspot) and address the media went over. */
@Serializable
data class CandidatePairStats(
    val localType: String? = null,
    val localAddress: String? = null,
    val localNetworkType: String? = null,
    val remoteType: String? = null,
    val remoteAddress: String? = null,
    val protocol: String? = null,
    val currentRttMs: Double? = null
)

/** One entry of an RTCStatsReport, without the org.webrtc types, so parsing is testable on the JVM. */
internal data class StatsEntry(val id: String, val type: String, val members: Map<String, Any?>)

/** Turns a libwebrtc stats report into our serializable summaries. Member names follow the W3C webrtc-stats spec. */
internal object StatsParser {
    fun outboundVideo(entries: List<StatsEntry>): OutboundVideoStats? {
        val byId = entries.associateBy { it.id }
        val rtp = entries.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: return null
        val m = rtp.members
        val encoded = m.long("framesEncoded")
        return OutboundVideoStats(
            codec = codecOf(m, byId),
            encoderImplementation = m.string("encoderImplementation"),
            powerEfficientEncoder = m["powerEfficientEncoder"] as? Boolean,
            framesEncoded = encoded,
            framesSent = m.long("framesSent"),
            keyFramesEncoded = m.long("keyFramesEncoded"),
            framesPerSecond = m.double("framesPerSecond"),
            frameWidth = m.long("frameWidth"),
            frameHeight = m.long("frameHeight"),
            targetBitrateBps = m.double("targetBitrate"),
            qualityLimitationReason = m.string("qualityLimitationReason"),
            avgEncodeMs = perFrameMs(m.double("totalEncodeTime"), encoded),
            nackCount = m.long("nackCount"),
            retransmittedPackets = m.long("retransmittedPacketsSent")
        )
    }

    fun inboundVideo(entries: List<StatsEntry>): InboundVideoStats? {
        val byId = entries.associateBy { it.id }
        val rtp = entries.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" } ?: return null
        val m = rtp.members
        val decoded = m.long("framesDecoded")
        return InboundVideoStats(
            codec = codecOf(m, byId),
            decoderImplementation = m.string("decoderImplementation"),
            powerEfficientDecoder = m["powerEfficientDecoder"] as? Boolean,
            framesReceived = m.long("framesReceived"),
            framesDecoded = decoded,
            framesDropped = m.long("framesDropped"),
            framesPerSecond = m.double("framesPerSecond"),
            frameWidth = m.long("frameWidth"),
            frameHeight = m.long("frameHeight"),
            avgJitterBufferMs = perFrameMs(m.double("jitterBufferDelay"), m.long("jitterBufferEmittedCount")),
            avgDecodeMs = perFrameMs(m.double("totalDecodeTime"), decoded),
            freezeCount = m.long("freezeCount"),
            packetsLost = m.long("packetsLost"),
            nackCount = m.long("nackCount")
        )
    }

    /** The pair the transport selected, or else the nominated succeeded one. */
    fun selectedPair(entries: List<StatsEntry>): CandidatePairStats? {
        val byId = entries.associateBy { it.id }
        val selectedId = entries.firstOrNull { it.type == "transport" }?.members?.string("selectedCandidatePairId")
        val pair = selectedId?.let { byId[it] }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
            ?: return null
        val local = pair.members.string("localCandidateId")?.let { byId[it] }?.members
        val remote = pair.members.string("remoteCandidateId")?.let { byId[it] }?.members
        return CandidatePairStats(
            localType = local?.string("candidateType"),
            localAddress = local?.let { address(it) },
            localNetworkType = local?.string("networkType"),
            remoteType = remote?.string("candidateType"),
            remoteAddress = remote?.let { address(it) },
            protocol = local?.string("protocol"),
            currentRttMs = pair.members.double("currentRoundTripTime")?.let { it * MS_PER_S }
        )
    }

    private fun codecOf(m: Map<String, Any?>, byId: Map<String, StatsEntry>): String? = m.string("codecId")?.let { byId[it] }?.members?.string("mimeType")

    private fun address(m: Map<String, Any?>): String? {
        val ip = m.string("address") ?: m.string("ip") ?: return null
        return m.long("port")?.let { "$ip:$it" } ?: ip
    }

    private fun perFrameMs(totalSeconds: Double?, count: Long?): Double? =
        if (totalSeconds == null || count == null || count <= 0) null else totalSeconds / count * MS_PER_S

    // libwebrtc maps uint32 to Long, uint64 to BigInteger, int32 to Integer: accept any Number.
    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    private const val MS_PER_S = 1_000.0
}
