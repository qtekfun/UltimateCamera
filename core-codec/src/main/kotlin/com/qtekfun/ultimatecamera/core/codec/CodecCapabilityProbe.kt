package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import kotlinx.serialization.Serializable

@Serializable
data class EncoderInfo(
    val name: String,
    val codec: VideoCodec,
    val hardware: Boolean,
    val vendor: Boolean,
    val tenBitProfile: Boolean,
    val profiles: List<Int>,
    val maxWidth: Int,
    val maxHeight: Int,
    val bitrateRangeBps: List<Int>,
    /** Whether the codec says it can do these size@fps combinations (frame-rate range check, not a promise). */
    val supports: Map<String, Boolean>,
    /** Performance points the vendor declares (API 29+), e.g. "3840x2160@60". Empty when none are declared. */
    val performancePoints: List<String>,
    val bitrateModes: List<String>
)

/** Lists the video encoders the device exposes, to feed the capability catalogue (Phase 0). */
object CodecCapabilityProbe {
    private val checkedModes = listOf(1920 to 1080, 3840 to 2160).flatMap { (w, h) -> listOf(30, 60, 120).map { Triple(w, h, it) } }

    fun encoders(): List<EncoderInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .flatMap { info -> VideoCodec.entries.filter { codec -> info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } }.map { info to it } }
        .mapNotNull { (info, codec) -> describe(info, codec) }

    /** Null for a codec that does not describe itself as a video encoder (no video or encoder capabilities). */
    private fun describe(info: MediaCodecInfo, codec: VideoCodec): EncoderInfo? {
        val caps = info.getCapabilitiesForType(codec.mime)
        val video = caps.videoCapabilities ?: return null
        val profiles = caps.profileLevels.map { it.profile }.distinct().sorted()
        val tenBit = when (codec) {
            VideoCodec.HEVC -> profiles.any { it == CodecProfileLevel.HEVCProfileMain10 || it == CodecProfileLevel.HEVCProfileMain10HDR10 }
            VideoCodec.AV1 -> profiles.any { it == CodecProfileLevel.AV1ProfileMain10 || it == CodecProfileLevel.AV1ProfileMain10HDR10 }
            VideoCodec.H264 -> false
        }
        val encoderCaps = caps.encoderCapabilities ?: return null
        val modes = buildList {
            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) add("CBR")
            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) add("VBR")
            if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) add("CQ")
        }
        return EncoderInfo(
            name = info.name,
            codec = codec,
            hardware = info.isHardwareAccelerated,
            vendor = info.isVendor,
            tenBitProfile = tenBit,
            profiles = profiles,
            maxWidth = video.supportedWidths.upper,
            maxHeight = video.supportedHeights.upper,
            bitrateRangeBps = listOf(video.bitrateRange.lower, video.bitrateRange.upper),
            supports = checkedModes.associate { (w, h, fps) -> "${w}x$h@$fps" to video.areSizeAndRateSupported(w, h, fps.toDouble()) },
            performancePoints = video.supportedPerformancePoints.orEmpty().map { it.toString() },
            bitrateModes = modes
        )
    }

    /** The preferred encoder for [config]: hardware first, and with a 10-bit profile when [EncoderConfig.tenBit]. */
    fun pick(encoders: List<EncoderInfo>, config: EncoderConfig): EncoderInfo? = encoders
        .filter { it.codec == config.codec && (!config.tenBit || it.tenBitProfile) }
        .sortedWith(compareByDescending<EncoderInfo> { it.hardware }.thenByDescending { it.supports["${config.width}x${config.height}@${config.fps}"] == true })
        .firstOrNull()
}
