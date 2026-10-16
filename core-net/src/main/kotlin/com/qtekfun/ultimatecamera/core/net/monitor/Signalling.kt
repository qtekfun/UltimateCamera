package com.qtekfun.ultimatecamera.core.net.monitor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Signalling for the monitor link: one JSON object per line over a plain TCP connection on the LAN. The camera phone
 * listens, the monitor connects to its IP. Both sides send [Hello] first; the camera then sends the [Offer], the monitor
 * the [Answer], and both trickle [Ice] candidates.
 *
 * Spike only: no authentication or encryption on this socket. The media itself is DTLS-SRTP, but pairing (QR key,
 * fingerprint check) comes later (docs/01).
 */
@Serializable
sealed interface SignallingMessage {
    /** First message on both sides; a different [protocol] ends the session. */
    @Serializable
    @SerialName("hello")
    data class Hello(val protocol: Int, val role: MonitorRole) : SignallingMessage

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : SignallingMessage

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : SignallingMessage

    /** A trickled ICE candidate, as in RTCIceCandidateInit. */
    @Serializable
    @SerialName("ice")
    data class Ice(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String) : SignallingMessage

    /** Orderly end of the session, with an optional reason for the peer's result. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String? = null) : SignallingMessage
}

/** Which end of the monitor link a phone is. */
@Serializable
enum class MonitorRole {
    /** The phone that records and sends the proxy video. */
    CAMERA,

    /** The phone that shows the video. */
    MONITOR
}

/** Newline-delimited JSON codec for [SignallingMessage]. JSON escapes the CR/LF of SDP, so a message is always one line. */
object SignallingCodec {
    const val PROTOCOL_VERSION = 1

    /** Longest line accepted; an SDP offer with a few codecs is a few kB. */
    const val MAX_LINE_CHARS = 64 * 1024

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(message: SignallingMessage): String = json.encodeToString(SignallingMessage.serializer(), message)

    /** Parses one line; throws [IllegalArgumentException] for anything that is not a known message. */
    fun decode(line: String): SignallingMessage {
        require(line.length <= MAX_LINE_CHARS) { "signalling line too long: ${line.length} chars" }
        return try {
            json.decodeFromString(SignallingMessage.serializer(), line)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("bad signalling message: ${e.message}", e)
        }
    }
}
