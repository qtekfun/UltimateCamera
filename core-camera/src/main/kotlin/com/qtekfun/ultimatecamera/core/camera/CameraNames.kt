package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles

/** Human-readable names for Camera2 integer constants, so reports and the capability catalogue can be read by people. */
object CameraNames {
    fun capability(value: Int): String = CAPABILITIES[value] ?: "UNKNOWN_$value"

    fun dynamicRangeProfile(value: Long): String = when (value) {
        DynamicRangeProfiles.STANDARD -> "STANDARD"
        DynamicRangeProfiles.HLG10 -> "HLG10"
        DynamicRangeProfiles.HDR10 -> "HDR10"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO -> "DOLBY_VISION_10B_HDR_REF_PO"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_HDR_OEM"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO -> "DOLBY_VISION_10B_HDR_OEM_PO"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO -> "DOLBY_VISION_8B_HDR_REF_PO"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "DOLBY_VISION_8B_HDR_OEM"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO -> "DOLBY_VISION_8B_HDR_OEM_PO"
        else -> "UNKNOWN_$value"
    }

    fun facing(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    fun hardwareLevel(value: Int?): String = when (value) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN_$value"
    }

    fun videoStabilization(value: Int): String = when (value) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STABILIZATION"
        else -> "UNKNOWN_$value"
    }

    fun ois(value: Int): String = when (value) {
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
        else -> "UNKNOWN_$value"
    }

    fun tonemap(value: Int): String = when (value) {
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE -> "CONTRAST_CURVE"
        CameraMetadata.TONEMAP_MODE_FAST -> "FAST"
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.TONEMAP_MODE_GAMMA_VALUE -> "GAMMA_VALUE"
        CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> "PRESET_CURVE"
        else -> "UNKNOWN_$value"
    }

    /** REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES (API 34); a literal so the module still compiles against it on 33. */
    private const val COLOR_SPACE_PROFILES = 20

    private val CAPABILITIES = mapOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE to "BACKWARD_COMPATIBLE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "MANUAL_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "MANUAL_POST_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to "RAW",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING to "PRIVATE_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "READ_SENSOR_SETTINGS",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "BURST_CAPTURE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING to "YUV_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT to "DEPTH_OUTPUT",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to "CONSTRAINED_HIGH_SPEED_VIDEO",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING to "MOTION_TRACKING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME to "MONOCHROME",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA to "SECURE_IMAGE_DATA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA to "SYSTEM_CAMERA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING to "OFFLINE_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR to "ULTRA_HIGH_RESOLUTION_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING to "REMOSAIC_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT to "DYNAMIC_RANGE_TEN_BIT",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE to "STREAM_USE_CASE",
        COLOR_SPACE_PROFILES to "COLOR_SPACE_PROFILES"
    )

    /** True for keys a phone maker added (vendor tags), as opposed to the AOSP `android.*` keys. */
    fun isVendorKey(name: String): Boolean = !name.startsWith("android.")
}
