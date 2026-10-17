package com.qtekfun.ultimatecamera.core.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.StreamSpec
import com.qtekfun.ultimatecamera.core.capabilities.SensorHdrMode
import com.qtekfun.ultimatecamera.core.capabilities.VendorParameter
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.recording.probe.setVendor
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * HDR viewfinder (D63): on an HDR display the preview of a 10-bit HLG take is shown as HDR (the EGL window surface is
 * tagged BT.2020 HLG and the window asks for the HDR colour mode) instead of the HLG signal squeezed into SDR.
 */
object HdrViewfinder {
    /**
     * The preview carries the camera's HLG signal as it comes, so it can be shown as HDR: [allowed] (setting on and an
     * HDR display), a 10-bit take, no UC-Log and no monitor LUT. A monitor LUT outputs Rec.709 SDR (D53) and UC-Log is an
     * SDR transfer, so both keep the SDR preview.
     */
    fun hlgPreview(allowed: Boolean, tenBit: Boolean, setup: ColorSetup): Boolean = allowed && tenBit && !setup.ucLog && setup.monitorLut == null

    /**
     * Headroom the window asks for (API 35 `Window.setDesiredHdrHeadroom`): HLG's reference white sits at 203 nits and
     * its peak at 1000 nits, about 4.9×; a viewfinder does not need the panel's full peak (battery, heat), so 4×.
     */
    const val DESIRED_HEADROOM = 4f
}

/** The HDR viewfinder's switches inside [CameraController]. */
internal class PreviewHdrState {
    @Volatile var allowed = false
    val active = MutableStateFlow(false)

    /** Tags [renderer]'s preview HLG or not after a change of profile ([tenBit], null when closed), colour or setting. */
    fun update(renderer: CameraRenderer?, tenBit: Boolean?, setup: ColorSetup) {
        val on = tenBit != null && HdrViewfinder.hlgPreview(allowed, tenBit, setup) && renderer?.canTagPreviewHlg == true
        renderer?.previewHlg = on
        active.value = on
    }
}

/** A size and rate a vendor variant was measured to hold at (any lower rate of the same size is held too). */
data class HeldMode(val width: Int, val height: Int, val fps: Int)

/**
 * Vendor session parameters and operating mode for the camera stream (experimental sensor HDR, D63), applied only to
 * the [held] modes: a profile the variant was not measured at always opens plain.
 */
data class VendorSession(val parameters: List<VendorParameter>, val sessionType: Int? = null, val held: List<HeldMode> = emptyList()) {
    fun allows(width: Int, height: Int, fps: Int): Boolean = held.any { it.width == width && it.height == height && it.fps >= fps }

    companion object {
        fun of(mode: SensorHdrMode, held: List<HeldMode>): VendorSession = VendorSession(mode.parameters, mode.sessionType, held)
    }
}

/** Opening the main camera stream with an optional [VendorSession] (D63). */
internal object VendorStreams {
    /**
     * The stream of [spec], with [vendor]'s listed keys and operating mode when given; a refused vendor session is
     * opened again plain, so the toggle can never cost the user the mode. Returns the stream and whether it has [vendor].
     */
    suspend fun open(
        manager: CameraManager,
        spec: StreamSpec,
        target: Surface,
        handler: Handler,
        chars: CameraCharacteristics,
        vendor: VendorSession?,
        onLost: (String) -> Unit
    ): Pair<CameraStream, Boolean> {
        if (vendor != null) {
            val keys = chars.availableCaptureRequestKeys.map { it.name }.toSet()
            val params = vendor.parameters.filter { it.key in keys }
            if (params.isNotEmpty() || vendor.sessionType != null) {
                try {
                    val vendorSpec = spec.copy(sessionType = vendor.sessionType)
                    return CameraStream.open(manager, vendorSpec, target, handler, { b -> params.forEach { b.setVendor(it) } }, onLost) to true
                } catch (_: SessionRejectedException) {
                    // Probed to hold, refused now (another OS build?): the plain session below.
                } catch (_: IllegalArgumentException) {
                    // A vendor key of an unexpected type.
                }
            }
        }
        return CameraStream.open(manager, spec, target, handler, onLost = onLost) to false
    }
}
