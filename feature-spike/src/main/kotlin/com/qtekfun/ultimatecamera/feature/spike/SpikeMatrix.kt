package com.qtekfun.ultimatecamera.feature.spike

import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.feature.spike.pipeline.PipelineConfig

/**
 * The Phase 0 pipeline matrix. It climbs from what a modest phone should hold (1080p30 8-bit) to the base target
 * (4K60 10-bit with LUT, REQ-NFR-001), and isolates the LUT's cost (no LUT vs hardware vs manual interpolation), so the
 * report says where each phone stops and why.
 */
object SpikeMatrix {
    fun default(encoders: List<EncoderInfo>): List<PipelineConfig> = buildList {
        add(PipelineConfig(1920, 1080, 30, VideoCodec.H264, tenBit = false, lut = LutInterpolation.HARDWARE, bitrateMbps = 20))
        add(PipelineConfig(1920, 1080, 60, VideoCodec.HEVC, tenBit = false, lut = LutInterpolation.HARDWARE, bitrateMbps = 30))
        add(PipelineConfig(1920, 1080, 30, VideoCodec.HEVC, tenBit = true, lut = LutInterpolation.HARDWARE, bitrateMbps = 25))
        add(PipelineConfig(3840, 2160, 30, VideoCodec.HEVC, tenBit = true, lut = LutInterpolation.HARDWARE, bitrateMbps = 60))
        add(PipelineConfig(3840, 2160, 60, VideoCodec.HEVC, tenBit = true, lut = null, bitrateMbps = 100))
        add(PipelineConfig(3840, 2160, 60, VideoCodec.HEVC, tenBit = true, lut = LutInterpolation.HARDWARE, bitrateMbps = 100))
        add(PipelineConfig(3840, 2160, 60, VideoCodec.HEVC, tenBit = true, lut = LutInterpolation.MANUAL_TRILINEAR, bitrateMbps = 100))
        if (encoders.any { it.codec == VideoCodec.AV1 && it.hardware && it.tenBitProfile }) {
            add(PipelineConfig(3840, 2160, 30, VideoCodec.AV1, tenBit = true, lut = LutInterpolation.HARDWARE, bitrateMbps = 40))
        }
    }

    /** What the camera phone records while it streams to the monitor in the WebRTC latency spike. */
    val monitored = PipelineConfig(3840, 2160, 30, VideoCodec.HEVC, tenBit = true, lut = LutInterpolation.HARDWARE, bitrateMbps = 60)

    /** The base target, run for minutes to see thermal throttling. */
    val sustained = PipelineConfig(3840, 2160, 60, VideoCodec.HEVC, tenBit = true, lut = LutInterpolation.HARDWARE, bitrateMbps = 100)
}
