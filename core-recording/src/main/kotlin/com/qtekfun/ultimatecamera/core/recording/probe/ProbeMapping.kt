package com.qtekfun.ultimatecamera.core.recording.probe

import android.hardware.camera2.params.DynamicRangeProfiles
import com.qtekfun.ultimatecamera.core.camera.CameraInfo
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedCamera
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedEncoder
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.SensorHdrExtension
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.EncoderConfig
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Glue between the capability catalogue's plain types and the Camera2 / MediaCodec / GL types (pure, unit-tested). */
object ProbeMapping {
    fun codec(type: CodecType): VideoCodec = when (type) {
        CodecType.H264 -> VideoCodec.H264
        CodecType.HEVC -> VideoCodec.HEVC
        CodecType.AV1 -> VideoCodec.AV1
    }

    fun codecType(codec: VideoCodec): CodecType = when (codec) {
        VideoCodec.H264 -> CodecType.H264
        VideoCodec.HEVC -> CodecType.HEVC
        VideoCodec.AV1 -> CodecType.AV1
    }

    /** Camera2 `DynamicRangeProfiles` constant of [range]; null for SDR (no profile set on the output). */
    fun dynamicRangeProfile(range: DynamicRange): Long? = when (range) {
        DynamicRange.STANDARD -> null
        DynamicRange.HLG10 -> DynamicRangeProfiles.HLG10
        DynamicRange.HDR10 -> DynamicRangeProfiles.HDR10
        DynamicRange.HDR10_PLUS -> DynamicRangeProfiles.HDR10_PLUS
    }

    /** GL interpolation of the LUT pass, or null when the configuration records without it. */
    fun interpolation(lut: LutMode): LutInterpolation? = when (lut) {
        LutMode.NONE -> null
        LutMode.HARDWARE -> LutInterpolation.HARDWARE
        LutMode.MANUAL_TRILINEAR -> LutInterpolation.MANUAL_TRILINEAR
    }

    /** What the probe encodes: the recording bitrate of the same mode, so the encoder is loaded as in a real take. */
    fun encoderConfig(config: VideoConfig): EncoderConfig = EncoderConfig(
        codec = codec(config.codec),
        width = config.width,
        height = config.height,
        fps = config.fps,
        bitrateBps = RecordingProfile.defaultBitrate(config.width, config.height, config.fps, config.tenBit),
        tenBit = config.tenBit
    )

    /** The camera screen's mode for a probed configuration (the LUT choice is the screen's, not the profile's). */
    fun profile(config: VideoConfig): RecordingProfile = RecordingProfile(config.width, config.height, config.fps, codec(config.codec), config.tenBit)

    fun advertisedCamera(info: CameraInfo): AdvertisedCamera = AdvertisedCamera(
        id = info.id,
        facing = info.facing,
        physicalIds = info.physicalIds,
        videoModes = info.videoModes.map { AdvertisedMode(it.width, it.height, it.maxFps) },
        tenBitProfiles = info.dynamicRangeProfiles.mapNotNull(DynamicRange::parse).filter { it.tenBit },
        recommendedTenBitProfile = info.recommendedTenBitProfile?.let(DynamicRange::parse)?.takeIf { it.tenBit },
        vendorRequestKeys = info.vendorRequestKeys,
        vendorCharacteristicKeys = info.vendorCharacteristicKeys,
        vendorSessionKeys = info.vendorSessionKeys,
        vendorHdrValues = info.vendorHdrValues,
        photoHdr = info.photoHdr,
        depthSources = info.depthSources
    ).let { it.copy(sensorHdr = SensorHdrExtension.detect(it)?.description) }

    /** Hardware encoders only: the planner treats a configuration without one as unsupported (software cannot keep up). */
    fun advertisedEncoders(encoders: List<EncoderInfo>): List<AdvertisedEncoder> = encoders.filter { it.hardware }.map {
        AdvertisedEncoder(it.name, codecType(it.codec), it.tenBitProfile, it.supports)
    }
}

/** Frame rate and drops over a measured window, from the encoder's frame count and camera timestamps at both ends. */
data class FrameWindow(val measuredFps: Double, val droppedFrames: Int) {
    companion object {
        /**
         * [framesAtStart] / [framesAtEnd] are the encoded frame counts and [ptsAtStartUs] / [ptsAtEndUs] the camera
         * timestamps of the last frame at each end; [gapDrops] the frames missing between consecutive frames in the
         * window (the renderer's gap count). Drops are the larger of the gaps and the shortfall against the nominal rate
         * over the span less [DRIFT_TOLERANCE]: a sensor running at 59.9 instead of 60 fps is not dropping frames (the
         * Pixel 8 4K60 8-bit probe reported 1 "drop" per 4 s from that alone), but any real gap, and a sustained
         * shortfall beyond the drift (e.g. an encoder discarding frames), still count.
         */
        fun of(framesAtStart: Int, ptsAtStartUs: Long, framesAtEnd: Int, ptsAtEndUs: Long, targetFps: Int, gapDrops: Int = 0): FrameWindow {
            val delivered = framesAtEnd - framesAtStart
            val spanUs = ptsAtEndUs - ptsAtStartUs
            if (ptsAtStartUs < 0 || delivered <= 0 || spanUs <= 0) return FrameWindow(0.0, 0)
            val nominal = spanUs * targetFps / MICROS_PER_SECOND.toDouble()
            val shortfall = (nominal.roundToInt() - delivered) - ceil(nominal * DRIFT_TOLERANCE).toInt()
            val dropped = maxOf(gapDrops, shortfall, 0)
            return FrameWindow(delivered * MICROS_PER_SECOND.toDouble() / spanUs, dropped)
        }

        /** Clock drift between the sensor's real rate and its nominal one that is not counted as drops (0.5 %). */
        const val DRIFT_TOLERANCE = 0.005

        private const val MICROS_PER_SECOND = 1_000_000L
    }
}
