package com.qtekfun.ultimatecamera.core.capabilities

import kotlinx.serialization.Serializable

/** Encoders the catalogue knows about (mirrors core-codec's VideoCodec without depending on MediaCodec). */
@Serializable
enum class CodecType { H264, HEVC, AV1 }

/** Camera2 dynamic range profile of the camera stream. Everything but [STANDARD] is a 10-bit stream. */
@Serializable
enum class DynamicRange {
    STANDARD,
    HLG10,
    HDR10,
    HDR10_PLUS;

    val tenBit: Boolean get() = this != STANDARD

    companion object {
        /** Preference when a camera offers several 10-bit profiles and recommends none of these (same as the spike). */
        val TEN_BIT_PREFERENCE = listOf(HLG10, HDR10, HDR10_PLUS)

        /** Parses Camera2 profile names as `CameraNames.dynamicRangeProfile` writes them; vendor profiles return null. */
        fun parse(name: String): DynamicRange? = entries.firstOrNull { it.name == name }
    }
}

/** LUT pass in the recording path. [NONE] records without it (flat Log; a LUT may still be shown on the monitor). */
@Serializable
enum class LutMode(internal val costFactor: Double) {
    NONE(1.0),
    HARDWARE(1.1),
    MANUAL_TRILINEAR(1.3)
}

/**
 * One recording configuration the probe engine can try and the selector can pick. [vendorMode] names a vendor sensor
 * mode tried as a variant of the configuration (e.g. [SensorHdrExtension.MODE]); null is the plain Camera2 session.
 * Results of a variant never stand for the plain configuration, nor the other way round ([covers]).
 */
@Serializable
data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: CodecType,
    val dynamicRange: DynamicRange,
    val lut: LutMode,
    val vendorMode: String? = null
) {
    val tenBit: Boolean get() = dynamicRange.tenBit
    val pixels: Long get() = width.toLong() * height

    /**
     * Relative pipeline load (pixels per second, weighted for 10-bit and the LUT pass). Only used to order probes and
     * to step down; it is a heuristic, never a substitute for a measurement.
     */
    val cost: Double get() = pixels * fps * (if (tenBit) TEN_BIT_COST else 1.0) * lut.costFactor

    /** Same wording as the spike's `PipelineConfig.label`, so catalogue seeds and reports match. */
    val label: String
        get() = "${width}x$height@$fps ${codec.name} ${if (tenBit) "10-bit" else "8-bit"} ${if (lut == LutMode.NONE) "NO_LUT" else lut.name}" +
            vendorMode?.let { " +$it" }.orEmpty()

    /** At least as demanding as [other] in every dimension, with the same codec (so [other] is no harder to hold). */
    fun covers(other: VideoConfig): Boolean = codec == other.codec && pixels >= other.pixels && fps >= other.fps &&
        (tenBit || !other.tenBit) && lut.costFactor >= other.lut.costFactor && vendorMode == other.vendorMode

    private companion object {
        const val TEN_BIT_COST = 1.25
    }
}
