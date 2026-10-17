package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.abs

/**
 * Where the depth of a portrait photo comes from (decision D73), in order of preference. [key] is persisted and
 * exported: never rename it. [experimental] sources are offered only with Settings › Experimental on (D37).
 */
enum class DepthSource(val key: String, val experimental: Boolean = false) {
    /** The camera's own depth output: `DEPTH_OUTPUT` with DEPTH16 (ToF or the maker's stereo depth). */
    DEPTH_CAMERA("depth16"),

    /** The maker's portrait mode through the Camera2 extension `EXTENSION_BOKEH`; its result is saved as is. */
    VENDOR_BOKEH("extension_bokeh"),

    /** Two physical back cameras of a logical camera, disparity estimated by block matching (ours). */
    STEREO("stereo", experimental = true)
}

/** A stereo pair behind a logical camera: [main] gives the framing, [second] (as wide or wider) the disparity. */
data class StereoPair(val main: String, val second: String, val mainOptics: LensOptics, val secondOptics: LensOptics) {
    /**
     * The centre fraction of [second]'s frame that sees what [main] sees (1 = same field of view); horizontal field of
     * view ratio from focal length over sensor width.
     */
    val secondCrop: Float
        get() {
            val main = mainOptics.focalMm / mainOptics.sensorWidthMm
            val second = secondOptics.focalMm / secondOptics.sensorWidthMm
            return (second / main).coerceIn(MIN_CROP, 1f)
        }

    private companion object {
        const val MIN_CROP = 0.1f
    }
}

/**
 * What a camera offers for depth, read from Camera2 (never assumed per brand). [depth16] holds DEPTH16 output sizes of
 * the camera itself (empty when it has none) and [pointCloud] whether it lists DEPTH_POINT_CLOUD (recorded only: we
 * need a dense map). [bokehExtension] is true when `EXTENSION_BOKEH` is listed with JPEG and preview sizes. [stereo] is
 * the physical pair when the camera is a logical multi-camera with two usable physical cameras of the same facing.
 */
data class DepthSupport(
    val depth16: List<PixelSize> = emptyList(),
    val pointCloud: Boolean = false,
    val bokehExtension: Boolean = false,
    val stereo: StereoPair? = null
) {
    /** Every source this camera has, in preference order, experimental ones included. */
    val sources: List<DepthSource>
        get() = buildList {
            if (depth16.isNotEmpty()) add(DepthSource.DEPTH_CAMERA)
            if (bokehExtension) add(DepthSource.VENDOR_BOKEH)
            if (stereo != null) add(DepthSource.STEREO)
        }

    /** Labels for the capability catalogue and the diagnostics export, e.g. `depth16`, `extension_bokeh`, `stereo:2+3`. */
    fun labels(): List<String> = buildList {
        if (depth16.isNotEmpty()) add(DepthSource.DEPTH_CAMERA.key)
        if (pointCloud) add("depth_point_cloud")
        if (bokehExtension) add(DepthSource.VENDOR_BOKEH.key)
        stereo?.let { add("${DepthSource.STEREO.key}:${it.main}+${it.second}") }
    }
}

/** One physical camera of a logical camera, as the stereo pair choice needs it. */
data class PhysicalLens(val id: String, val facing: LensFacing, val optics: LensOptics?, val yuv: Boolean)

/** Picks portrait depth sources (pure, so every phone class is unit-tested). */
object PortraitSelector {
    /** The 35 mm-equivalent focal length a "main" camera has (wide, ~24–28 mm). */
    private const val MAIN_EQUIVALENT_MM = 26f

    /**
     * What the portrait chip offers with Settings › Photo › Portrait [enabled] (owner: optional, off by default): with
     * it off nothing is probed ([probe] is not called), so no depth stream or extension session ever opens.
     */
    inline fun offered(enabled: Boolean, experimental: Boolean, probe: () -> DepthSupport): List<DepthSource> =
        if (enabled) available(probe(), experimental) else emptyList()

    /** What the portrait chip offers: [DepthSupport.sources] without experimental ones unless [experimental] is on. */
    fun available(support: DepthSupport, experimental: Boolean): List<DepthSource> = support.sources.filter { experimental || !it.experimental }

    /** The source a portrait shot uses, or null: no depth, no portrait (we never fake the blur). */
    fun choose(support: DepthSupport, experimental: Boolean): DepthSource? = available(support, experimental).firstOrNull()

    /**
     * The stereo pair of a logical camera facing [facing] from its [physical] cameras: the main is the one closest to a
     * 26 mm equivalent, the second the closest of the others that sees at least as wide (its centre is cropped to the
     * main's view). Both need optics and a YUV output. Null when there is no such pair.
     */
    fun stereoPair(facing: LensFacing, physical: List<PhysicalLens>): StereoPair? {
        val usable = physical.filter { it.facing == facing && it.yuv }
            .mapNotNull { lens -> lens.optics?.takeIf { it.equivalentMm > 0f }?.let { lens.id to it } }
        val main = usable.minByOrNull { abs(it.second.equivalentMm - MAIN_EQUIVALENT_MM) } ?: return null
        val second = usable.filter { it.first != main.first && it.second.equivalentMm <= main.second.equivalentMm }
            .maxByOrNull { it.second.equivalentMm } ?: return null
        return StereoPair(main.first, second.first, main.second, second.second)
    }
}
