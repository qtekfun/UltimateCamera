package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaFormat
import kotlinx.serialization.Serializable

/** Video codecs the app can record with (REQ-CAM-004). */
@Serializable
enum class VideoCodec(val mime: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC),
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1)
}

/** What to encode. [tenBit] selects HEVC Main10 / AV1 Main10 with BT.2020 HLG signalling. */
@Serializable
data class EncoderConfig(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val tenBit: Boolean,
    val iFrameIntervalSeconds: Int = 1
) {
    init {
        require(width > 0 && height > 0 && fps > 0 && bitrateBps > 0) { "invalid encoder config $this" }
        require(!tenBit || codec != VideoCodec.H264) { "H.264 is 8-bit only in this app" }
    }

    val label: String get() = "${codec.name} ${width}x$height@$fps ${if (tenBit) "10-bit" else "8-bit"} ${bitrateBps / 1_000_000}Mbps"
}
