package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/** Reads [CameraLenses] from Camera2 without opening any camera (REQ-CAM-001). */
object LensReader {
    /** Every camera of `getCameraIdList`, with the physical cameras behind each logical one; unreadable ones are skipped. */
    fun read(manager: CameraManager): List<CameraLenses> = manager.cameraIdList.mapNotNull { id -> runCatching { describe(manager, id) }.getOrNull() }

    /** The catalogue of this phone's lenses. */
    fun catalog(manager: CameraManager): LensCatalog = LensCatalog.build(read(manager))

    private fun describe(manager: CameraManager, id: String): CameraLenses {
        val c = manager.getCameraCharacteristics(id)
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val logical = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        val physical = if (logical) {
            c.physicalCameraIds.mapNotNull { pid -> runCatching { lenses(pid, manager.getCameraCharacteristics(pid), emptyList()) }.getOrNull() }
        } else {
            emptyList()
        }
        return lenses(id, c, physical)
    }

    /** `LENS_FACING` as a [LensFacing]; anything but back or front (external, unknown) counts as external. */
    fun facing(value: Int?): LensFacing = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
        else -> LensFacing.EXTERNAL
    }

    private fun lenses(id: String, c: CameraCharacteristics, physical: List<CameraLenses>): CameraLenses {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return CameraLenses(
            id = id,
            facing = facing(c.get(CameraCharacteristics.LENS_FACING)),
            optics = if (focal != null && size != null) LensOptics(focal, size.width, size.height) else null,
            physical = physical,
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper }
        )
    }
}
