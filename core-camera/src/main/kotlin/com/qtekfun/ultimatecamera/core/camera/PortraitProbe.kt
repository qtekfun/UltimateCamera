package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/**
 * Reads [DepthSupport] for one camera from Camera2 (D73): the DEPTH_OUTPUT capability and its DEPTH16 /
 * DEPTH_POINT_CLOUD outputs, the `EXTENSION_BOKEH` extension, and the physical cameras of a logical multi-camera.
 * Extension queries can block or throw on some phones: call it off the main thread; a failing query means "not offered".
 */
object PortraitProbe {
    fun read(manager: CameraManager, cameraId: String): DepthSupport {
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return DepthSupport()
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val depthOutput = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in capabilities
        val depth16 = if (depthOutput && map != null) sizes { map.getOutputSizes(ImageFormat.DEPTH16) } else emptyList()
        val pointCloud = depthOutput && map != null && sizes { map.getOutputSizes(ImageFormat.DEPTH_POINT_CLOUD) }.isNotEmpty()
        val stereo = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities) {
            PortraitSelector.stereoPair(facing(chars), chars.physicalCameraIds.mapNotNull { physical(manager, it) })
        } else {
            null
        }
        return DepthSupport(depth16 = depth16, pointCloud = pointCloud, bokehExtension = bokeh(manager, cameraId), stereo = stereo)
    }

    private fun bokeh(manager: CameraManager, cameraId: String): Boolean {
        val ext = runCatching { manager.getCameraExtensionCharacteristics(cameraId) }.getOrNull() ?: return false
        val listed = runCatching { ext.supportedExtensions }.getOrNull().orEmpty()
        val id = CameraExtensionCharacteristics.EXTENSION_BOKEH
        return id in listed && PhotoHdrProbe.extensionSizes(ext, id, ImageFormat.JPEG).isNotEmpty() && PhotoHdrProbe.previewSizes(ext, id).isNotEmpty()
    }

    private fun physical(manager: CameraManager, id: String): PhysicalLens? {
        val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val yuv = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { m -> sizes { m.getOutputSizes(ImageFormat.YUV_420_888) } }.orEmpty()
        return PhysicalLens(id, facing(c), if (focal != null && size != null) LensOptics(focal, size.width, size.height) else null, yuv.isNotEmpty())
    }

    private fun facing(c: CameraCharacteristics): LensFacing = when (c.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        else -> LensFacing.EXTERNAL
    }

    private fun sizes(read: () -> Array<android.util.Size>?): List<PixelSize> =
        runCatching { read().orEmpty().map { PixelSize(it.width, it.height) } }.getOrDefault(emptyList())
}
