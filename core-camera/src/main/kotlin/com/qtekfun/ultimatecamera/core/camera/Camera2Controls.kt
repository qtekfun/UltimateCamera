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

    fun ranges(chars: CameraCharacteristics): ControlRanges {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val postProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val kelvin = postProcessing && CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes && calibrations(chars) != null
        return ControlRanges(
            isoRange = if (manualSensor && iso != null) iso.lower..iso.upper else null,
            exposureTimeRangeNs = if (manualSensor && exposure != null) exposure.lower..exposure.upper else null,
            evRange = ev?.let { it.lower..it.upper } ?: 0..0,
            evStep = evStep?.toFloat() ?: (1f / 3),
            minFocusDiopters = minFocus,
            manualFocus = manualSensor && minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
            whiteBalances = PRESETS.filterValues { it in awbModes }.keys + WhiteBalance.AUTO,
            kelvinRange = if (kelvin) KELVIN_RANGE else null,
            aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            activeArray = active?.let { SensorRect(0, 0, it.width(), it.height()) } ?: SensorRect(0, 0, 1, 1)
        )
    }

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
        calibrations: Pair<ColorCalibration, ColorCalibration?>?
    ) {
        if (c.manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exposureTimeNs)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, ControlMath.frameDurationNs(spec.fps))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.exposureCompensation)
            if (ranges.aeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
        }
        if (c.manualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters)
        } else if (ranges.minFocusDiopters > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        c.meteringPoint?.let { point ->
            val r = ControlMath.imageToSensor(point, crop, spec.width, spec.height)
            val regions = arrayOf(MeteringRectangle(r.left, r.top, r.width, r.height, MeteringRectangle.METERING_WEIGHT_MAX))
            if (ranges.maxAeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
            if (ranges.maxAfRegions > 0 && !c.manualFocus) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        }
        val transform = autoTransform
        if (c.whiteBalance == WhiteBalance.MANUAL && transform != null && calibrations != null) {
            val g = KelvinMath.kelvinToGains(c.kelvin, calibrations.first, calibrations.second)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.red, g.greenEven, g.greenOdd, g.blue))
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, PRESETS[c.whiteBalance] ?: CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (ranges.awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
        }
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
