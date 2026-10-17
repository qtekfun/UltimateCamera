package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest

/**
 * The HAL's best processing for stills (D74): HIGH_QUALITY tone mapping, noise reduction and edge enhancement, each
 * only when the camera lists that mode (a request with an unlisted mode is an error on some HALs).
 */
class StillQuality(private val tonemap: Boolean, private val noise: Boolean, private val edge: Boolean) {
    fun applyTo(builder: CaptureRequest.Builder) {
        if (tonemap) builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        if (noise) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (edge) builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
    }

    companion object {
        fun of(chars: CameraCharacteristics): StillQuality = from(
            chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
            chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        )

        /** From the listed modes (null = key absent). Pure, for tests. */
        fun from(tonemapModes: IntArray?, noiseModes: IntArray?, edgeModes: IntArray?) = StillQuality(
            tonemap = tonemapModes?.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY) == true,
            noise = noiseModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true,
            edge = edgeModes?.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY) == true
        )
    }

    override fun toString(): String = "StillQuality(tonemap=$tonemap, noise=$noise, edge=$edge)"

    val modes: Triple<Boolean, Boolean, Boolean> get() = Triple(tonemap, noise, edge)
}
