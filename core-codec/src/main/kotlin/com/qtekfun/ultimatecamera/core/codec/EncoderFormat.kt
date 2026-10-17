package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat

/**
 * The integer keys of the MediaFormat [SurfaceVideoEncoder] configures for [config]. Kept apart from the codec so the
 * choices (realtime priority, operating rate, 10-bit profile, colour signalling) are testable on the JVM.
 */
internal fun encoderFormatKeys(config: EncoderConfig): Map<String, Int> = buildMap {
    put(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
    put(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
    put(MediaFormat.KEY_FRAME_RATE, config.fps)
    put(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
    // Realtime priority: the codec must keep up with the camera, not batch for throughput.
    put(MediaFormat.KEY_PRIORITY, 0)
    // Without the real rate several vendors keep the encoder clock low and drop frames at 4K60.
    put(MediaFormat.KEY_OPERATING_RATE, config.fps)
    if (config.tenBit) {
        put(
            MediaFormat.KEY_PROFILE,
            if (config.codec == VideoCodec.AV1) CodecProfileLevel.AV1ProfileMain10 else CodecProfileLevel.HEVCProfileMain10
        )
    }
    config.effectiveColorTag?.let { tag ->
        put(
            MediaFormat.KEY_COLOR_STANDARD,
            if (tag == ColorTag.SDR_BT709) MediaFormat.COLOR_STANDARD_BT709 else MediaFormat.COLOR_STANDARD_BT2020
        )
        put(
            MediaFormat.KEY_COLOR_TRANSFER,
            if (tag == ColorTag.HLG_BT2020) MediaFormat.COLOR_TRANSFER_HLG else MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        put(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }
}
