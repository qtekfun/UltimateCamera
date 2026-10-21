package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap

/**
 * What one camera offers for video beyond the catalogue (D111): every output size per format, the maximum-resolution
 * map, and the values of the vendor keys that look like size tables. Read-only, no camera opened, no device identifier.
 */
data class CameraReportEntry(
    val id: String,
    /** How the id was found: `listed`, `physical of <id>`, or `unlisted` (answered although not in the camera list). */
    val origin: String,
    val facing: String,
    val pixelArray: String?,
    val activeArray: String?,
    /** Format name → output sizes, largest first. */
    val sizes: Map<String, List<String>>,
    /** The same for `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`; empty when the camera has none. */
    val maxResolutionSizes: Map<String, List<String>>,
    val dynamicRangeProfiles: List<String>,
    /** Vendor key → its value as text, for keys whose name looks like a size or stream table. */
    val vendorValues: Map<String, String>
)

/** The text the Settings diagnostics show and copy (pure, unit-tested). */
object CameraReportText {
    /** Sizes shown per format: enough to see the large ones and where 4K sits. */
    const val MAX_SIZES = 12

    /** Ints shown per vendor value before it is cut. */
    const val MAX_VALUES = 400

    /** Vendor keys worth reading: their name says they describe sizes or streams. */
    fun isSizeKey(name: String): Boolean {
        val n = name.lowercase()
        return CameraNames.isVendorKey(name) && SIZE_WORDS.any { it in n }
    }

    fun format(entries: List<CameraReportEntry>): String = buildString {
        entries.forEach { e ->
            appendLine("camera ${e.id} (${e.origin}) ${e.facing}")
            e.pixelArray?.let { appendLine("  pixel array $it") }
            e.activeArray?.let { appendLine("  active array $it") }
            if (e.dynamicRangeProfiles.isNotEmpty()) appendLine("  dynamic range ${e.dynamicRangeProfiles.joinToString()}")
            e.sizes.forEach { (format, sizes) -> appendLine("  $format ${sizes.take(MAX_SIZES).joinToString()}") }
            e.maxResolutionSizes.forEach { (format, sizes) -> appendLine("  max-res $format ${sizes.take(MAX_SIZES).joinToString()}") }
            e.vendorValues.forEach { (key, value) -> appendLine("  $key = $value") }
            appendLine()
        }
    }.trimEnd()

    /** A vendor array as text, cut after [MAX_VALUES] values. */
    fun values(values: List<Any>): String = values.take(MAX_VALUES).joinToString(",") + if (values.size > MAX_VALUES) ",… (${values.size} values)" else ""

    /** `WxH` sorted by area, largest first, without repeats. */
    fun sizes(pairs: List<Pair<Int, Int>>): List<String> = pairs.distinct().sortedByDescending { (w, h) -> w.toLong() * h }.map { (w, h) -> "${w}x$h" }

    private val SIZE_WORDS = listOf("scaler", "stream", "size", "dimension", "resolution")
}

/** Reads [CameraReportEntry] for every camera the app can see (D111). */
class CameraReportReader(private val manager: CameraManager) {
    fun read(): List<CameraReportEntry> {
        val listed = manager.cameraIdList.toList()
        val physical = listed.flatMap { id -> characteristics(id)?.physicalCameraIds.orEmpty().map { it to id } }
            .filterNot { (p, _) -> p in listed }
            .distinctBy { it.first }
        val known = listed + physical.map { it.first }
        // Some phones answer for ids they do not list; the low numbers cover every layout seen.
        val unlisted = (0 until UNLISTED_IDS).map { it.toString() }.filterNot { it in known }.filter { characteristics(it) != null }
        return listed.mapNotNull { entry(it, "listed") } +
            physical.mapNotNull { (p, of) -> entry(p, "physical of $of") } +
            unlisted.mapNotNull { entry(it, "unlisted") }
    }

    private fun characteristics(id: String): CameraCharacteristics? = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()

    private fun entry(id: String, origin: String): CameraReportEntry? {
        val c = characteristics(id) ?: return null
        return CameraReportEntry(
            id = id,
            origin = origin,
            facing = CameraNames.facing(c.get(CameraCharacteristics.LENS_FACING)),
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { "${it.width}x${it.height}" },
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { "${it.width()}x${it.height()}" },
            sizes = sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)),
            maxResolutionSizes = sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)),
            dynamicRangeProfiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
                .sorted()
                .map(CameraNames::dynamicRangeProfile),
            vendorValues = c.keys.map { it.name }.filter(CameraReportText::isSizeKey).sorted().mapNotNull { name -> value(c, name)?.let { name to it } }.toMap()
        )
    }

    private fun sizes(map: StreamConfigurationMap?): Map<String, List<String>> {
        if (map == null) return emptyMap()
        val texture = runCatching { map.getOutputSizes(SurfaceTexture::class.java) }.getOrNull().orEmpty()
        return buildMap {
            if (texture.isNotEmpty()) put("PRIVATE", CameraReportText.sizes(texture.map { it.width to it.height }))
            FORMATS.forEach { (name, format) ->
                val s = runCatching { map.getOutputSizes(format) }.getOrNull().orEmpty()
                if (s.isNotEmpty()) put(name, CameraReportText.sizes(s.map { it.width to it.height }))
            }
        }
    }

    /** A vendor value of any array or scalar type the HAL may use; null when none fits. */
    private fun value(c: CameraCharacteristics, name: String): String? = TYPES.firstNotNullOfOrNull { read -> tryGet { read(c, name) } }

    private fun tryGet(block: () -> String?): String? = try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val UNLISTED_IDS = 64
        val FORMATS = listOf(
            "YUV_420_888" to ImageFormat.YUV_420_888,
            "JPEG" to ImageFormat.JPEG,
            "RAW_SENSOR" to ImageFormat.RAW_SENSOR
        )
        val TYPES: List<(CameraCharacteristics, String) -> String?> = listOf(
            { c, n -> c.get(CameraCharacteristics.Key(n, IntArray::class.java))?.let { CameraReportText.values(it.toList()) } },
            { c, n -> c.get(CameraCharacteristics.Key(n, LongArray::class.java))?.let { CameraReportText.values(it.toList()) } },
            { c, n -> c.get(CameraCharacteristics.Key(n, FloatArray::class.java))?.let { CameraReportText.values(it.toList()) } },
            { c, n -> c.get(CameraCharacteristics.Key(n, ByteArray::class.java))?.let { CameraReportText.values(it.toList()) } },
            { c, n -> c.get(CameraCharacteristics.Key(n, Int::class.javaObjectType))?.toString() },
            { c, n -> c.get(CameraCharacteristics.Key(n, Long::class.javaObjectType))?.toString() },
            { c, n -> c.get(CameraCharacteristics.Key(n, Byte::class.javaObjectType))?.toString() }
        )
    }
}
