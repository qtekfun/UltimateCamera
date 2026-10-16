package com.qtekfun.ultimatecamera.core.net.monitor

import com.qtekfun.ultimatecamera.core.net.NetworkLink
import kotlinx.serialization.Serializable

/** Video codec the camera prefers for the proxy; the other side must also support it, or negotiation falls back. */
@Serializable
enum class MonitorCodec(val rtpName: String) {
    /** Most compatible: every Android phone has a hardware H.264 encoder and decoder. */
    H264("H264"),

    /** Needs RTP H.265 support in libwebrtc on both sides; unverified in m149 (docs/research/webrtc-android.md §7). */
    H265("H265")
}

/** What the camera sends. The proxy is rendered by our GPU pipeline at exactly [width] x [height]. */
@Serializable
data class MonitorVideoConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateBps: Int = 2_000_000,
    val codec: MonitorCodec = MonitorCodec.H264
) {
    init {
        require(width > 0 && height > 0 && fps > 0 && bitrateBps > 0)
    }
}

/**
 * Network and latency settings, recorded in every [MonitorLinkResult]. Items marked unverified are expected to help
 * but have not been measured yet (docs/research/webrtc-android.md); the spike measures them.
 */
@Serializable
data class MonitorNetworkOptions(
    /** TCP port of the camera's signalling socket. */
    val port: Int = SignallingChannel.DEFAULT_PORT,
    /**
     * PeerConnectionFactory.Options.disableNetworkMonitor. With the Android network monitor off, native code lists
     * interfaces with getifaddrs, which may reveal the phone's own hotspot interface that stock libwebrtc ignores
     * (unverified; research doc §3.5 mitigation 2). Off by default; the spike runs both.
     */
    val disableNetworkMonitor: Boolean = false,
    /**
     * Receiver field trials WebRTC-ForcePlayoutDelay (min 0, max 0) and WebRTC-ZeroPlayoutDelay (no pacing, decode
     * queue of one), and the sender's WebRTC-ForceSendPlayoutDelay (playout-delay header extension 0/0): frames are
     * rendered as soon as they are decoded instead of after an adaptive jitter-buffer delay (unverified on Android).
     */
    val zeroPlayoutDelay: Boolean = true,
    /** RTCConfiguration.enableDscp: marks packets so Wi-Fi WMM may queue them as video (unverified). */
    val enableDscp: Boolean = true
)

/**
 * Reads the frame counter from the stamp strip. [luma] holds the Y (luma, 0..255) samples of one row, taken at
 * y = round(0.02 * height), the vertical centre of a strip covering the top 4 % of the frame; only the first [width]
 * entries are valid. Returns the counter, or null when the row is not a readable stamp. Called on a libwebrtc decoder
 * thread for every received frame: keep it fast and allocation-free.
 */
fun interface StampDecoder {
    fun decode(luma: IntArray, width: Int): Int?
}

/** Where a link is in its life. */
enum class MonitorLinkState {
    IDLE,

    /** Camera: listening for the monitor. Monitor: connecting to the camera. */
    SIGNALLING,

    /** Offer/answer done, ICE and DTLS in progress. */
    CONNECTING,

    /** Media flows and the data channel is open. */
    CONNECTED,
    FAILED,
    CLOSED
}

/** Monitor-side frame counts (the camera fills [MonitorLinkResult.latency] instead). */
@Serializable
data class MonitorReceiveCounts(
    val framesReceived: Int,
    /** Frames whose stamp the [StampDecoder] read. */
    val stampsDecoded: Int,
    val echoesSent: Int,
    /** Frames whose luma row could not be read (no decoder, conversion failed). */
    val stampReadFailures: Int
)

/** Result of one monitor-link measurement, from either phone. */
@Serializable
data class MonitorLinkResult(
    val role: MonitorRole,
    /** The other phone's address as seen by the signalling socket. */
    val peer: String?,
    /** Camera only: what it was asked to send. */
    val video: MonitorVideoConfig? = null,
    val network: MonitorNetworkOptions,
    /** libwebrtc field trials in force for this link. */
    val fieldTrials: String,
    val latency: MonitorLatency? = null,
    val receive: MonitorReceiveCounts? = null,
    val outbound: OutboundVideoStats? = null,
    val inbound: InboundVideoStats? = null,
    val candidatePair: CandidatePairStats? = null,
    /** Non-fatal findings: a codec preference libwebrtc refused, a connection that dropped and came back. */
    val notes: List<String> = emptyList(),
    val error: String? = null,
    val link: NetworkLink? = null
)
