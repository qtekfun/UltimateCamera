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

    /** A vendor value as text: arrays through [values], scalars as they print; null for nothing or an unknown type. */
    fun describe(value: Any?): String? = when (value) {
        null -> null
        is IntArray -> values(value.toList())
        is LongArray -> values(value.toList())
        is FloatArray -> values(value.toList())
        is ByteArray -> values(value.toList())
        is Number -> value.toString()
        else -> null
    }

    /**
     * The ids to report and how each was found: the [listed] ones, then the physical cameras behind them ([physicalOf])
     * not listed themselves, then any id below [probeUpTo] that [answers] although nobody named it.
     */
    fun ids(listed: List<String>, physicalOf: (String) -> List<String>, probeUpTo: Int, answers: (String) -> Boolean): List<Pair<String, String>> {
        val physical = listed.flatMap { of -> physicalOf(of).map { it to "physical of $of" } }.filterNot { (id, _) -> id in listed }.distinctBy { it.first }
        val known = listed.toSet() + physical.map { it.first }
        val unlisted = (0 until probeUpTo).map { it.toString() }.filterNot { it in known }.filter(answers).map { it to "unlisted" }
        return listed.map { it to "listed" } + physical + unlisted
    }

    /** Format name → sizes, largest first; formats without any size are left out. */
    fun sizeTable(formats: List<Pair<String, List<Pair<Int, Int>>>>): Map<String, List<String>> =
        formats.filter { it.second.isNotEmpty() }.associate { (name, pairs) -> name to sizes(pairs) }

    private val SIZE_WORDS = listOf("scaler", "stream", "size", "dimension", "resolution")
}

/** Reads [CameraReportEntry] for every camera the app can see (D111). */
class CameraReportReader(private val manager: CameraManager) {
    fun read(): List<CameraReportEntry> = // Some phones answer for ids they do not list; the low numbers cover every layout seen.
        CameraReportText.ids(manager.cameraIdList.toList(), { characteristics(it)?.physicalCameraIds.orEmpty().toList() }, UNLISTED_IDS) {
            characteristics(it) != null
        }.mapNotNull { (id, origin) -> characteristics(id)?.let { entry(id, origin, it) } }

    private fun characteristics(id: String): CameraCharacteristics? = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()

    private fun entry(id: String, origin: String, c: CameraCharacteristics) = CameraReportEntry(
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

    private fun sizes(map: StreamConfigurationMap?): Map<String, List<String>> = map?.let { m ->
        val texture = "PRIVATE" to runCatching { m.getOutputSizes(SurfaceTexture::class.java) }.getOrNull().orEmpty().map { it.width to it.height }
        CameraReportText.sizeTable(
            listOf(texture) +
                FORMATS.map { (name, format) -> name to runCatching { m.getOutputSizes(format) }.getOrNull().orEmpty().map { it.width to it.height } }
        )
    }.orEmpty()

    /** A vendor value of any array or scalar type the HAL may use; null when none fits (a wrong type throws). */
    private fun value(c: CameraCharacteristics, name: String): String? =
        TYPES.firstNotNullOfOrNull { type -> runCatching { CameraReportText.describe(c.get(CameraCharacteristics.Key(name, type))) }.getOrNull() }

    private companion object {
        const val UNLISTED_IDS = 64
        val FORMATS = listOf(
            "YUV_420_888" to ImageFormat.YUV_420_888,
            "JPEG" to ImageFormat.JPEG,
            "RAW_SENSOR" to ImageFormat.RAW_SENSOR
        )
        val TYPES: List<Class<*>> = listOf(
            IntArray::class.java,
            LongArray::class.java,
            FloatArray::class.java,
            ByteArray::class.java,
            Int::class.javaObjectType,
            Long::class.javaObjectType,
            Byte::class.javaObjectType
        )
    }
}
