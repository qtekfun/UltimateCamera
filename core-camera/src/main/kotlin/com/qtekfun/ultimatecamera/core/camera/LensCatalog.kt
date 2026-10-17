package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Which way a camera looks. */
enum class LensFacing { BACK, FRONT, EXTERNAL }

/** One lens as the phone describes it: `LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]` on `SENSOR_INFO_PHYSICAL_SIZE`. */
data class LensOptics(val focalMm: Float, val sensorWidthMm: Float, val sensorHeightMm: Float) {
    /** 35 mm-equivalent focal length (by the diagonal), 0 when the phone reports no size. */
    val equivalentMm: Float
        get() {
            val diagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
            return if (diagonal <= 0f || focalMm <= 0f) 0f else focalMm * FULL_FRAME_DIAGONAL_MM / diagonal
        }

    companion object {
        /** Diagonal of a 36×24 mm frame. */
        const val FULL_FRAME_DIAGONAL_MM = 43.267f
    }
}

/**
 * A camera of `CameraManager.getCameraIdList`, read from its characteristics: its own optics, the physical cameras behind
 * it when it is a `LOGICAL_MULTI_CAMERA` ([physical], empty otherwise) and its `CONTROL_ZOOM_RATIO_RANGE`.
 */
data class CameraLenses(
    val id: String,
    val facing: LensFacing,
    val optics: LensOptics?,
    val physical: List<CameraLenses> = emptyList(),
    val zoomRange: ClosedFloatingPointRange<Float>? = null
)

/** What a lens stop is, for its name: ultra-wide, the main lens, a crop of it, a telephoto, or the front camera. */
enum class LensKind { ULTRA_WIDE, MAIN, CROP, TELE, FRONT }

/**
 * One stop of the lens selector: open [cameraId] (if not open) and set `CONTROL_ZOOM_RATIO` to [zoomRatio]. [factor] is
 * the magnification relative to the main back lens (0.5 for an ultra-wide, 1 for the main one, 3 or 5 for a tele).
 */
data class LensStop(val kind: LensKind, val cameraId: String, val zoomRatio: Float, val factor: Float) {
    /** Stock-camera style: `0.5`, `1`, `2`, `5`, `1.5` (the UI adds `×` to the selected one). */
    val label: String get() = LensCatalog.formatFactor(factor)
}

/**
 * The lenses of this phone as a stock camera app offers them (REQ-CAM-001), read from what Camera2 reports, never
 * assumed per brand. The main lens is the first back camera of the list (the same one [mainId] the screen opens). Lenses
 * behind a logical camera are reached with `CONTROL_ZOOM_RATIO` on that camera (the HAL switches physical lenses
 * smoothly, also during a take); other back cameras of the list are opened by id. A 2× crop stop is added where the zoom
 * reaches it and no lens sits there.
 */
class LensCatalog private constructor(
    /** Back stops, widest first. */
    val back: List<LensStop>,
    /** The front camera, or null. */
    val front: LensStop?,
    /** The main back camera id, or null without back cameras. */
    val mainId: String?,
    private val kinds: Map<String, LensKind>,
    private val factors: Map<String, Float>
) {
    /** The kind of camera [id] (any listed or physical id), or null when unknown. */
    fun kindOf(id: String): LensKind? = kinds[id]

    /** Magnification of camera [id] at zoom ratio 1 relative to the main back lens (1 for unknown ids and the front). */
    fun factorOf(id: String): Float = factors[id] ?: 1f

    /** The stops that apply to [cameraId]: the back ones for a back camera, none for the front one. */
    fun stopsFor(cameraId: String): List<LensStop> = if (kindOf(cameraId) == LensKind.FRONT) emptyList() else back

    /**
     * The stop to highlight with camera [cameraId] at [zoomRatio]: the stop on that camera with the largest zoom ratio
     * not above the current one (so 1.4× still highlights 1×); null when none applies (e.g. front camera).
     */
    fun selected(cameraId: String, zoomRatio: Float): LensStop? {
        val onCamera = back.filter { it.cameraId == cameraId }.sortedBy { it.zoomRatio }
        if (onCamera.isEmpty()) return null
        return onCamera.lastOrNull { it.zoomRatio <= zoomRatio * (1 + TOLERANCE) } ?: onCamera.first()
    }

    /** Zoom relative to the main lens shown while pinching: camera [cameraId]'s own factor times [zoomRatio]. */
    fun displayZoom(cameraId: String, zoomRatio: Float): Float = factorOf(cameraId) * zoomRatio

    companion object {
        private const val TOLERANCE = 0.02f
        private const val ULTRA_WIDE_BELOW = 0.8f
        private const val MAIN_BELOW = 1.25f
        private const val SAME_FOCAL = 0.05f
        private const val CROP_FACTOR = 2f
        private const val CROP_NEAR = 0.2f

        /** Builds the catalogue from [cameras] in `getCameraIdList` order. */
        fun build(cameras: List<CameraLenses>): LensCatalog {
            val backs = cameras.filter { it.facing == LensFacing.BACK }
            val builder = backs.firstOrNull()?.let { main ->
                BackBuilder(main).apply {
                    addLogical()
                    addCrop()
                    backs.drop(1).forEach(::addSeparate)
                }
            }
            val kinds = builder?.kinds ?: mutableMapOf()
            val fronts = cameras.filter { it.facing == LensFacing.FRONT }
            fronts.forEach {
                kinds[it.id] = LensKind.FRONT
                it.physical.forEach { p -> kinds.putIfAbsent(p.id, LensKind.FRONT) }
            }
            val front = fronts.firstOrNull()?.let { LensStop(LensKind.FRONT, it.id, 1f, 1f) }
            val ordered = builder?.stops.orEmpty().distinctBy { it.label }.sortedBy { it.factor }
            return LensCatalog(ordered, front, backs.firstOrNull()?.id, kinds, builder?.factors.orEmpty())
        }

        /** The back stops around [main], the reference lens (factor 1). */
        private class BackBuilder(private val main: CameraLenses) {
            val kinds = mutableMapOf(main.id to LensKind.MAIN)
            val factors = mutableMapOf(main.id to 1f)
            val stops = mutableListOf(LensStop(LensKind.MAIN, main.id, 1f, 1f))
            private val reference = main.optics?.equivalentMm ?: 0f

            private fun factor(o: LensOptics?): Float? = o?.equivalentMm?.takeIf { it > 0f && reference > 0f }?.let { it / reference }

            /** Records [camera]'s kind and factor; null when its optics are unknown. */
            private fun describe(camera: CameraLenses): Pair<LensKind, Float>? {
                val f = factor(camera.optics) ?: return null
                val k = classify(f, sameFocal = sameFocal(camera.optics, main.optics))
                kinds.putIfAbsent(camera.id, k)
                factors.putIfAbsent(camera.id, f)
                return k to f
            }

            /** Lenses behind the logical main camera: reached by zoom ratio, one session for all of them. */
            fun addLogical() {
                val range = main.zoomRange ?: return
                for (p in main.physical) {
                    val (k, f) = describe(p) ?: continue
                    val reachable = f >= range.start * (1 - TOLERANCE) && f <= range.endInclusive
                    if (k != LensKind.MAIN && reachable) stops += LensStop(k, main.id, f.coerceIn(range), f)
                }
            }

            /** A 2× crop where the zoom reaches it and no lens sits there. */
            fun addCrop() {
                val range = main.zoomRange ?: return
                if (range.endInclusive >= CROP_FACTOR && stops.none { abs(it.factor - CROP_FACTOR) < CROP_NEAR }) {
                    stops += LensStop(LensKind.CROP, main.id, CROP_FACTOR, CROP_FACTOR)
                }
            }

            /** Another back camera of the list: opened by id (not while recording). */
            fun addSeparate(camera: CameraLenses) {
                val (k, f) = describe(camera) ?: return
                val wide = k == LensKind.MAIN || k == LensKind.CROP
                if (!wide && stops.none { it.label == formatFactor(f) }) stops += LensStop(k, camera.id, 1f, f)
            }
        }

        private fun sameFocal(a: LensOptics?, b: LensOptics?): Boolean =
            a != null && b != null && b.focalMm > 0f && abs(a.focalMm - b.focalMm) <= b.focalMm * SAME_FOCAL

        private fun classify(factor: Float, sameFocal: Boolean): LensKind = when {
            factor < ULTRA_WIDE_BELOW -> LensKind.ULTRA_WIDE
            factor < MAIN_BELOW -> LensKind.MAIN
            // The main lens on a smaller crop of its sensor (Pixel 8: a half-size mode of the main camera).
            sameFocal -> LensKind.CROP
            else -> LensKind.TELE
        }

        /** `0.5` (tenths, rounded down, under 1), `2`, `5` (whole), `1.5` (otherwise one decimal). */
        fun formatFactor(factor: Float): String {
            if (factor < 1f - TOLERANCE) {
                val tenths = floor(factor * TENTHS + TOLERANCE) / TENTHS
                return "%.1f".format(java.util.Locale.ROOT, tenths)
            }
            val whole = factor.roundToInt()
            return if (abs(factor - whole) < factor * TOLERANCE * 2) whole.toString() else "%.1f".format(java.util.Locale.ROOT, factor)
        }

        private const val TENTHS = 10f
    }
}
