package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector

/** Camera2 side of the manual controls: reads [ControlRanges] from characteristics and writes [CameraControls] to requests. */
internal object Camera2Controls {
    /** Kelvin range offered when the camera allows manual colour gains. */
    private val KELVIN_RANGE = 2000..10000

    fun ranges(chars: CameraCharacteristics): ControlRanges = ranges(read(chars))

    /** The characteristics [ranges] needs, read once (the decisions are in the pure [ranges] overload). */
    private fun read(chars: CameraCharacteristics): ControlCharacteristics = ControlCharacteristics(
        capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty(),
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
        exposureTimeRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
        evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { it.lower..it.upper },
        evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat(),
        minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty(),
        awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(),
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { SensorRect(0, 0, it.width(), it.height()) },
        hasCalibration = calibrations(chars) != null,
        aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
        awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
        zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper },
        oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty(),
        stabilizationModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
    )

    /** What [raw] lets the user control: manual sensor and post-processing capabilities gate exposure, focus and Kelvin. */
    fun ranges(raw: ControlCharacteristics): ControlRanges {
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in raw.capabilities
        val postProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in raw.capabilities
        val kelvin = postProcessing && CameraMetadata.CONTROL_AWB_MODE_OFF in raw.awbModes && raw.hasCalibration
        return ControlRanges(
            isoRange = raw.isoRange?.takeIf { manualSensor },
            exposureTimeRangeNs = raw.exposureTimeRangeNs?.takeIf { manualSensor },
            evRange = raw.evRange ?: 0..0,
            evStep = raw.evStep ?: (1f / 3),
            minFocusDiopters = raw.minFocusDiopters,
            manualFocus = manualSensor && raw.minFocusDiopters > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in raw.afModes,
            whiteBalances = PRESETS.filterValues { it in raw.awbModes }.keys + WhiteBalance.AUTO,
            kelvinRange = if (kelvin) KELVIN_RANGE else null,
            aeLock = raw.aeLockAvailable,
            awbLock = raw.awbLockAvailable,
            maxAeRegions = raw.maxAeRegions,
            maxAfRegions = raw.maxAfRegions,
            activeArray = raw.activeArray ?: SensorRect(0, 0, 1, 1),
            zoomRange = raw.zoomRange ?: 1f..1f,
            ois = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in raw.oisModes,
            stabilizations = stabilizations(raw.stabilizationModes)
        )
    }

    /** Electronic stabilisation modes the camera offers, OFF always included. */
    fun stabilizations(chars: CameraCharacteristics): Set<VideoStabilization> =
        stabilizations(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty())

    /** [VideoStabilization]s among the `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` [modes], OFF always included. */
    fun stabilizations(modes: Set<Int>): Set<VideoStabilization> = VIDEO_STABILIZATION.filterValues { it in modes }.keys + VideoStabilization.OFF

    /** The `CONTROL_VIDEO_STABILIZATION_MODE` value of [mode]. */
    fun stabilizationMode(mode: VideoStabilization): Int = VIDEO_STABILIZATION.getValue(mode)

    private val VIDEO_STABILIZATION = mapOf(
        VideoStabilization.OFF to CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        VideoStabilization.ON to CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
        VideoStabilization.PREVIEW to CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    )

    /** The two reference calibrations (the second may be missing), or null when the camera reports none. */
    fun calibrations(chars: CameraCharacteristics): Pair<ColorCalibration, ColorCalibration?>? {
        fun read(illuminant: Number?, matrix: CameraCharacteristics.Key<ColorSpaceTransform>, cal: CameraCharacteristics.Key<ColorSpaceTransform>) =
            illuminant?.let { ILLUMINANT_KELVIN[it.toInt()] }?.let { k ->
                chars.get(matrix)?.let { m -> ColorCalibration(k, m.toFloats(), chars.get(cal)?.toFloats() ?: ColorCalibration.IDENTITY) }
            }
        val first = read(
            chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            CameraCharacteristics.SENSOR_COLOR_TRANSFORM1,
            CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1
        ) ?: return null
        val second = read(
            chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2),
            CameraCharacteristics.SENSOR_COLOR_TRANSFORM2,
            CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2
        )
        return Pair(first, second)
    }

    /** Writes [c] (already clamped) into [builder]; [crop] is the stream's crop region, [autoTransform] the last auto CCM. */
    @Suppress("LongParameterList")
    fun apply(
        builder: CaptureRequest.Builder,
        c: CameraControls,
        ranges: ControlRanges,
        spec: StreamSpec,
        crop: SensorRect,
        autoTransform: ColorSpaceTransform?,
        calibrations: Pair<ColorCalibration, ColorCalibration?>?,
        sceneKelvin: Int? = null
    ) {
        val p = plan(c, ranges, spec, crop, hasAutoTransform = autoTransform != null, calibrations = calibrations, sceneKelvin = sceneKelvin)
        builder.set(CaptureRequest.CONTROL_AE_MODE, p.aeMode)
        p.iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        p.exposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        p.frameDurationNs?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
        p.exposureCompensation?.let { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
        p.aeLock?.let { builder.set(CaptureRequest.CONTROL_AE_LOCK, it) }
        p.afMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
        p.focusDiopters?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        val metering = { r: SensorRect -> arrayOf(MeteringRectangle(r.left, r.top, r.width, r.height, MeteringRectangle.METERING_WEIGHT_MAX)) }
        p.aeRegion?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering(it)) }
        p.afRegion?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering(it)) }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, p.awbMode)
        val g = p.manualGains
        if (g != null && autoTransform != null) {
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.red, g.greenEven, g.greenOdd, g.blue))
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, autoTransform)
        }
        p.awbLock?.let { builder.set(CaptureRequest.CONTROL_AWB_LOCK, it) }
        p.zoomRatio?.let { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
        p.oisMode?.let { builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it) }
    }

    /**
     * The request values for [c] (already clamped), without Camera2: manual exposure turns AE off and pins the frame
     * duration to the stream's rate; manual Kelvin needs the camera's last automatic colour matrix ([hasAutoTransform])
     * and its calibrations, otherwise the preset (or AUTO) is used. AUTO with a [sceneKelvin] ([SceneAwb]) is sent the
     * same way as manual Kelvin at that temperature; without one it is the camera's own AWB.
     */
    @Suppress("LongParameterList")
    fun plan(
        c: CameraControls,
        ranges: ControlRanges,
        spec: StreamSpec,
        crop: SensorRect,
        hasAutoTransform: Boolean,
        calibrations: Pair<ColorCalibration, ColorCalibration?>?,
        sceneKelvin: Int? = null
    ): ControlPlan {
        val region = c.meteringPoint?.let { ControlMath.imageToSensor(it, crop, spec.width, spec.height) }
        val wbKelvin = SceneAwb.gainsKelvin(c, sceneKelvin)
        val manualWb = wbKelvin != null && hasAutoTransform && calibrations != null
        return ControlPlan(
            aeMode = if (c.manualExposure) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON,
            iso = c.iso.takeIf { c.manualExposure },
            exposureTimeNs = c.exposureTimeNs.takeIf { c.manualExposure },
            frameDurationNs = if (c.manualExposure) ControlMath.frameDurationNs(spec.fps) else null,
            exposureCompensation = c.exposureCompensation.takeIf { !c.manualExposure },
            aeLock = c.aeLock.takeIf { !c.manualExposure && ranges.aeLock },
            afMode = when {
                c.manualFocus -> CameraMetadata.CONTROL_AF_MODE_OFF
                ranges.minFocusDiopters > 0f -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else -> null
            },
            focusDiopters = c.focusDiopters.takeIf { c.manualFocus },
            aeRegion = region?.takeIf { ranges.maxAeRegions > 0 },
            afRegion = region?.takeIf { ranges.maxAfRegions > 0 && !c.manualFocus },
            awbMode = if (manualWb) CameraMetadata.CONTROL_AWB_MODE_OFF else PRESETS[c.whiteBalance] ?: CameraMetadata.CONTROL_AWB_MODE_AUTO,
            manualGains = calibrations?.takeIf { manualWb }?.let { KelvinMath.kelvinToGains(wbKelvin ?: c.kelvin, it.first, it.second) },
            awbLock = c.awbLock.takeIf { !manualWb && ranges.awbLock },
            zoomRatio = c.zoomRatio.takeIf { ranges.zoomable },
            oisMode = when {
                !ranges.ois -> null
                c.ois -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else -> CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
        )
    }

    private fun ColorSpaceTransform.toFloats(): FloatArray = FloatArray(9) { getElement(it % 3, it / 3).toFloat() }

    private val PRESETS = mapOf(
        WhiteBalance.AUTO to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        WhiteBalance.DAYLIGHT to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        WhiteBalance.CLOUDY to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        WhiteBalance.TUNGSTEN to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        WhiteBalance.FLUORESCENT to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    )

    /** Correlated colour temperature of each `SENSOR_REFERENCE_ILLUMINANT1` value (EXIF LightSource). */
    private val ILLUMINANT_KELVIN = mapOf(
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT to 5500,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT to 4200,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN to 2850,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH to 5500,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER to 5500,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER to 6500,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE to 7500,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT to 6430,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT to 5000,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT to 4150,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT to 3450,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A to 2856,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B to 4874,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C to 6774,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 to 5503,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65 to 6504,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 to 7504,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 to 5003,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN to 3200
    )
}

/** The raw characteristics behind [ControlRanges] (null / empty = not reported). */
internal data class ControlCharacteristics(
    val capabilities: Set<Int> = emptySet(),
    val isoRange: IntRange? = null,
    val exposureTimeRangeNs: LongRange? = null,
    val evRange: IntRange? = null,
    val evStep: Float? = null,
    val minFocusDiopters: Float = 0f,
    val afModes: Set<Int> = emptySet(),
    val awbModes: Set<Int> = emptySet(),
    val activeArray: SensorRect? = null,
    /** The camera reports at least the first reference calibration ([Camera2Controls.calibrations]). */
    val hasCalibration: Boolean = false,
    val aeLockAvailable: Boolean = false,
    val awbLockAvailable: Boolean = false,
    val maxAeRegions: Int = 0,
    val maxAfRegions: Int = 0,
    val zoomRange: ClosedFloatingPointRange<Float>? = null,
    val oisModes: Set<Int> = emptySet(),
    val stabilizationModes: Set<Int> = emptySet()
)

/** What [Camera2Controls.apply] writes into a request, as plain values; null leaves the key out. */
internal data class ControlPlan(
    val aeMode: Int,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val exposureCompensation: Int? = null,
    val aeLock: Boolean? = null,
    val afMode: Int? = null,
    val focusDiopters: Float? = null,
    val aeRegion: SensorRect? = null,
    val afRegion: SensorRect? = null,
    val awbMode: Int,
    /** Manual Kelvin: these gains plus the camera's automatic colour matrix, colour correction in matrix mode. */
    val manualGains: RggbGains? = null,
    val awbLock: Boolean? = null,
    val zoomRatio: Float? = null,
    val oisMode: Int? = null
)
