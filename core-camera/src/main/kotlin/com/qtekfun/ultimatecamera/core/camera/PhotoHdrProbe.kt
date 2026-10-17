package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/**
 * Reads [HdrSupport] for one camera from Camera2 (D64). Extension queries can block for seconds or throw on some
 * phones (Open Camera's research notes): call it off the main thread, and every query failing means "not offered".
 */
object PhotoHdrProbe {
    /** Camera2 extension constants for the extension methods. */
    val EXTENSION_IDS: Map<HdrMethod, Int> = mapOf(
        HdrMethod.EXTENSION_HDR to CameraExtensionCharacteristics.EXTENSION_HDR,
        HdrMethod.EXTENSION_AUTO to CameraExtensionCharacteristics.EXTENSION_AUTOMATIC,
        HdrMethod.EXTENSION_NIGHT to CameraExtensionCharacteristics.EXTENSION_NIGHT
    )

    /** Vendor extension services throw anything; a failure only hides the method. */
    fun read(manager: CameraManager, cameraId: String): HdrSupport {
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return HdrSupport()
        val ext = runCatching { manager.getCameraExtensionCharacteristics(cameraId) }.getOrNull()
        val listed = ext?.let { runCatching { it.supportedExtensions }.getOrNull() }.orEmpty()
        val usable = EXTENSION_IDS.filter { (_, id) ->
            id in listed && ext != null && extensionSizes(ext, id, ImageFormat.JPEG).isNotEmpty() && previewSizes(ext, id).isNotEmpty()
        }.keys
        val extJpegR = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ext != null) {
            usable.filter { extensionSizes(ext, EXTENSION_IDS.getValue(it), ImageFormat.JPEG_R).isNotEmpty() }.toSet()
        } else {
            emptySet()
        }
        return HdrSupport(extensions = usable, extensionJpegR = extJpegR, jpegR = jpegR(chars), bracket = bracket(chars))
    }

    fun extensionSizes(ext: CameraExtensionCharacteristics, extension: Int, format: Int): List<PixelSize> =
        runCatching { ext.getExtensionSupportedSizes(extension, format).map { PixelSize(it.width, it.height) } }.getOrDefault(emptyList())

    fun previewSizes(ext: CameraExtensionCharacteristics, extension: Int): List<PixelSize> =
        runCatching { ext.getExtensionSupportedSizes(extension, SurfaceTexture::class.java).map { PixelSize(it.width, it.height) } }
            .getOrDefault(emptyList())

    /** JPEG_R (API 34) listed as an output, with the HLG10 profile it is produced from. */
    fun jpegR(chars: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val hlg = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
            .contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10)
        return hlg && map.outputFormats.contains(ImageFormat.JPEG_R) && map.getOutputSizes(ImageFormat.JPEG_R).orEmpty().isNotEmpty()
    }

    /** A YUV still output and either manual exposure or a usable AE compensation range of at least ±1 EV. */
    fun bracket(chars: CameraCharacteristics): Boolean {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        if (map.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty()) return false
        return manualSensor(chars) || compensationSteps(chars, 1.0) != null
    }

    fun manualSensor(chars: CameraCharacteristics): Boolean =
        chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    /** AE compensation steps for [ev] stops (clamped to the range), or null when the range is under ±1 EV. */
    fun compensationSteps(chars: CameraCharacteristics, ev: Double): Int? {
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble()?.takeIf { it > 0 } ?: return null
        if (range.upper * step < 1.0 || range.lower * step > -1.0) return null
        return Math.round(ev / step).toInt().coerceIn(range.lower, range.upper)
    }
}
