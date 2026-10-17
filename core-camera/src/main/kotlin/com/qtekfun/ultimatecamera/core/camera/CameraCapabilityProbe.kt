package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlinx.serialization.Serializable

@Serializable
data class StreamMode(val width: Int, val height: Int, val maxFps: Int) {
    override fun toString(): String = "${width}x$height@$maxFps"
}

@Serializable
data class CameraInfo(
    val id: String,
    val physicalIds: List<String>,
    val facing: String,
    val hardwareLevel: String,
    val capabilities: List<String>,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val sensorSizeMm: List<Float>,
    val pixelArray: List<Int>,
    val isoRange: List<Int>,
    val exposureRangeNs: List<Long>,
    val aeTargetFpsRanges: List<String>,
    val videoStabilizationModes: List<String>,
    val opticalStabilizationModes: List<String>,
    val zoomRatioRange: List<Float>,
    val maxDigitalZoom: Float?,
    val minFocusDistanceDiopters: Float?,
    val dynamicRangeProfiles: List<String>,
    val recommendedTenBitProfile: String?,
    val colorSpaces: List<String>,
    val tonemapModes: List<String>,
    /** PRIVATE (GPU) output sizes from 1280x720 up with the fastest frame rate each allows. */
    val videoModes: List<StreamMode>,
    /** Constrained high-speed modes, e.g. "1920x1080@240". */
    val highSpeedModes: List<String>,
    val vendorCharacteristicKeys: List<String>,
    val vendorRequestKeys: List<String>,
    val vendorResultKeys: List<String>,
    /** Vendor keys among `getAvailableSessionKeys()`. */
    val vendorSessionKeys: List<String> = emptyList(),
    /** Values of HDR-looking vendor characteristics ("1,2"), read defensively; unreadable ones are left out (D63). */
    val vendorHdrValues: Map<String, String> = emptyMap(),
    /** HDR photo methods offered (D64, [HdrSupport.labels]), e.g. `extension_hdr`, `jpeg_r`, `bracket`. */
    val photoHdr: List<String> = emptyList(),
    /** Portrait depth sources (D73, [DepthSupport.labels]), e.g. `depth16`, `extension_bokeh`, `stereo:2+3`. */
    val depthSources: List<String> = emptyList()
)

/** Reads everything the capability catalogue needs from Camera2 without opening any camera. */
class CameraCapabilityProbe(private val manager: CameraManager) {
    /** All cameras the framework lists, plus the physical cameras behind each logical camera. */
    fun cameras(): List<CameraInfo> {
        val listed = manager.cameraIdList.toList()
        val physical = listed.flatMap { manager.getCameraCharacteristics(it).physicalCameraIds }.filterNot { it in listed }.distinct()
        return (listed + physical).mapNotNull { id -> runCatching { describe(id) }.getOrNull() }
    }

    fun describe(id: String): CameraInfo {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val videoModes = videoModes(sizes.map { PixelSize(it.width, it.height) }) { index ->
            map?.getOutputMinFrameDuration(SurfaceTexture::class.java, sizes[index]) ?: 0L
        }
        val highSpeed = highSpeedModes(
            map?.highSpeedVideoSizes.orEmpty().map { size ->
                PixelSize(size.width, size.height) to map?.getHighSpeedVideoFpsRangesFor(size).orEmpty().map { it.upper }
            }
        )
        val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        val colorSpaces = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)?.getSupportedColorSpaces(ImageFormat.PRIVATE).orEmpty().map { it.name }
        } else {
            emptyList()
        }
        val recommended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            c.get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)?.let(CameraNames::dynamicRangeProfile)
        } else {
            null
        }
        return CameraInfo(
            id = id,
            physicalIds = c.physicalCameraIds.toList(),
            facing = CameraNames.facing(c.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = CameraNames.hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(CameraNames::capability).orEmpty(),
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
            sensorSizeMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { listOf(it.width, it.height) }.orEmpty(),
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { listOf(it.width, it.height) }.orEmpty(),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { listOf(it.lower, it.upper) }.orEmpty(),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { listOf(it.lower, it.upper) }.orEmpty(),
            aeTargetFpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { "${it.lower}-${it.upper}" }.orEmpty(),
            videoStabilizationModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.map(CameraNames::videoStabilization).orEmpty(),
            opticalStabilizationModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.map(CameraNames::ois).orEmpty(),
            zoomRatioRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { listOf(it.lower, it.upper) }.orEmpty(),
            maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM),
            minFocusDistanceDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            dynamicRangeProfiles = profiles?.supportedProfiles.orEmpty().sorted().map(CameraNames::dynamicRangeProfile),
            recommendedTenBitProfile = recommended,
            colorSpaces = colorSpaces,
            tonemapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.map(CameraNames::tonemap).orEmpty(),
            videoModes = videoModes,
            highSpeedModes = highSpeed,
            vendorCharacteristicKeys = vendorKeys(c.keys.map { it.name }),
            vendorRequestKeys = vendorKeys(c.availableCaptureRequestKeys.map { it.name }),
            vendorResultKeys = vendorKeys(c.availableCaptureResultKeys.map { it.name }),
            vendorSessionKeys = vendorKeys(c.availableSessionKeys.map { it.name }),
            vendorHdrValues = VendorValueReader.hdrValues(c),
            photoHdr = PhotoHdrProbe.read(manager, id).labels(),
            depthSources = PortraitProbe.read(manager, id).labels()
        )
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MIN_VIDEO_WIDTH = 1280
        private const val MIN_VIDEO_HEIGHT = 720

        /** "WxH@fps" of each constrained high-speed size and the top of each of its fps ranges, without repeats. */
        fun highSpeedModes(sizes: List<Pair<PixelSize, List<Int>>>): List<String> =
            sizes.flatMap { (size, tops) -> tops.map { "${size.width}x${size.height}@$it" } }.distinct()

        /** The vendor keys among [names], sorted, for the catalogue. */
        fun vendorKeys(names: List<String>): List<String> = names.filter(CameraNames::isVendorKey).sorted()

        /**
         * The video modes among the GPU output [sizes]: 1280x720 and up, largest first, each with the fastest rate its
         * minimum frame duration ([minFrameNs] of the size's index in [sizes]) allows; 0 fps when the HAL reports none.
         */
        fun videoModes(sizes: List<PixelSize>, minFrameNs: (Int) -> Long): List<StreamMode> = sizes.indices
            .filter { sizes[it].width >= MIN_VIDEO_WIDTH && sizes[it].height >= MIN_VIDEO_HEIGHT }
            .sortedByDescending { sizes[it].area }
            .map { i ->
                val frameNs = minFrameNs(i)
                StreamMode(sizes[i].width, sizes[i].height, if (frameNs > 0) (NANOS_PER_SECOND / frameNs).toInt() else 0)
            }
    }
}

/**
 * Reads vendor characteristics whose names look HDR-related, as text, for the catalogue and the diagnostics export
 * (docs/research/open-camera-fv5.md §5.2.1: a wrong type throws IllegalArgumentException, which is caught; nothing else
 * is touched). Tried as an int array, then an int, then a byte.
 */
object VendorValueReader {
    fun hdrValues(c: CameraCharacteristics): Map<String, String> = hdrKeys(c.keys.map { it.name })
        .mapNotNull { name -> read(c, name)?.let { name to it } }
        .toMap()

    /** Vendor keys whose name mentions HDR (any case), sorted. */
    fun hdrKeys(names: List<String>): List<String> = names.filter { CameraNames.isVendorKey(it) && "hdr" in it.lowercase() }.sorted()

    private fun read(c: CameraCharacteristics, name: String): String? =
        tryGet { c.get(CameraCharacteristics.Key(name, IntArray::class.java))?.joinToString(",") }
            ?: tryGet { c.get(CameraCharacteristics.Key(name, Int::class.javaObjectType))?.toString() }
            ?: tryGet { c.get(CameraCharacteristics.Key(name, Byte::class.javaObjectType))?.toString() }

    private fun tryGet(block: () -> String?): String? = try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    }
}
