package com.qtekfun.ultimatecamera.core.gpu.meters

/*
 * Settings of the monitor aids (REQ-MON-001). Plain immutable data: the camera screen keeps them in its state and
 * hands a new instance to the renderers when the user changes something (never per frame). The renderers and the CPU
 * references read the same classes, so a setting means the same thing on the GPU and in the tests.
 *
 * Levels are percentages of the monitored signal in full range (0 % = code 0, 100 % = full scale), measured on Rec. 709
 * luma (`Y = 0.2126 R + 0.7152 G + 0.0722 B`) of the image as shown on the monitor, i.e. after the LUT. On an
 * ungraded Log image the levels are Log code values, as on any field monitor.
 *
 * The whole feature belongs to the activatable group `FeatureGroup.METERS` (core-settings): the camera screen creates
 * these renderers only while that group is enabled.
 */

/** Anamorphic desqueeze applied to the preview only; the recording keeps the squeezed image. */
enum class Desqueeze(val factor: Float) {
    NONE(1f),
    X1_33(1.33f),
    X1_5(1.5f),
    X1_8(1.8f),
    X2(2f)
}

/** Focus peaking: pixels whose luma gradient `|dY/dx| + |dY/dy|` (central differences) exceeds [threshold]. */
data class PeakingSettings(val enabled: Boolean = false, val color: Int = 0xFFFF2828.toInt(), val threshold: Float = 0.12f) {
    init {
        require(threshold > 0f && threshold <= 2f) { "peaking threshold $threshold outside (0, 2]" }
    }
}

/** One zebra pattern: stripes over pixels whose level is in [[minPercent], [maxPercent]] (both inclusive). */
data class ZebraBand(val enabled: Boolean, val minPercent: Float, val maxPercent: Float, val color: Int) {
    init {
        require(minPercent <= maxPercent) { "zebra band $minPercent..$maxPercent is empty" }
    }
}

/**
 * Two zebra patterns with opposite stripe directions: by default [primary] marks highlights at or above 95 % (the max
 * is above 100 % so clipped white is always included) and [secondary] a 65–75 % window for skin. Stripes are
 * [stripeWidthPx] wide; when [animated] they move [speedPxPerFrame] pixels per frame.
 */
data class ZebraSettings(
    val primary: ZebraBand = ZebraBand(enabled = false, minPercent = 95f, maxPercent = 110f, color = 0xFF000000.toInt()),
    val secondary: ZebraBand = ZebraBand(enabled = false, minPercent = 65f, maxPercent = 75f, color = 0xFFFFFFFF.toInt()),
    val stripeWidthPx: Int = 4,
    val animated: Boolean = false,
    val speedPxPerFrame: Int = 1
) {
    init {
        require(stripeWidthPx in 1..64) { "stripe width $stripeWidthPx" }
        require(speedPxPerFrame in 0..64) { "stripe speed $speedPxPerFrame" }
    }
}

/** A false-colour band: levels in [[minPercent], [maxPercent]) are painted [color] (opaque ARGB). */
data class FalseColorBand(val minPercent: Float, val maxPercent: Float, val color: Int, val label: String)

/**
 * False colour replaces the image by its luma in grey, except for levels inside one of [bands] (first match wins, at
 * most [MAX_BANDS]).
 */
data class FalseColorSettings(val enabled: Boolean = false, val bands: List<FalseColorBand> = ARRI_LIKE) {
    init {
        require(bands.size <= MAX_BANDS) { "at most $MAX_BANDS false-colour bands" }
    }

    companion object {
        const val MAX_BANDS = 8

        /**
         * Modelled on the ARRI ALEXA false-colour table (levels of the monitored Rec. 709 video signal): purple = black
         * clipping, blue = just above black, green = 18 % grey, pink = one stop over grey (typical light skin),
         * yellow = just below white clipping, red = white clipping. Everything else is shown as grey luma. The edges
         * are ARRI's published percentages; the colours are our own approximations.
         */
        val ARRI_LIKE = listOf(
            FalseColorBand(0f, 2.5f, 0xFF8C1EC8.toInt(), "black clip"),
            FalseColorBand(2.5f, 4f, 0xFF1E5AFF.toInt(), "near black"),
            FalseColorBand(38f, 42f, 0xFF28C846.toInt(), "18% grey"),
            FalseColorBand(52f, 56f, 0xFFFF82BE.toInt(), "grey +1 stop"),
            FalseColorBand(97f, 99f, 0xFFFFE628.toInt(), "near white"),
            FalseColorBand(99f, 110f, 0xFFFF1E1E.toInt(), "white clip")
        )
    }
}

/**
 * Composition guides, drawn as thin lines over the active area: the frame-line rectangle when [frameLineAspect] is
 * set (outside it a matte of [matteOpacity] darkens the image), otherwise the whole image.
 */
data class GuideSettings(
    val thirds: Boolean = false,
    val centre: Boolean = false,
    /** Safe-area outlines as fractions of the active area, e.g. [ACTION_SAFE] and [TITLE_SAFE]. */
    val safeAreas: List<Float> = emptyList(),
    /** Width / height of the frame lines, e.g. [ASPECT_2_39]; null = none. */
    val frameLineAspect: Float? = null,
    val matteOpacity: Float = 0.5f,
    val lineColor: Int = 0xCCFFFFFF.toInt(),
    val lineWidthPx: Int = 2
) {
    init {
        require(safeAreas.all { it > 0f && it <= 1f }) { "safe areas must be fractions in (0, 1]" }
        require(frameLineAspect == null || frameLineAspect > 0f) { "frame-line aspect $frameLineAspect" }
        require(matteOpacity in 0f..1f) { "matte opacity $matteOpacity" }
        require(lineWidthPx in 1..16) { "line width $lineWidthPx" }
    }

    companion object {
        const val ACTION_SAFE = 0.93f
        const val TITLE_SAFE = 0.90f
        const val ASPECT_2_39 = 2.39f
        const val ASPECT_1_85 = 1.85f
        const val ASPECT_4_3 = 4f / 3f
        const val ASPECT_1_1 = 1f
        const val ASPECT_9_16 = 9f / 16f
    }
}

/** Everything the preview overlay pass draws. Never applied to the recording. */
data class MonitorOverlaySettings(
    val peaking: PeakingSettings = PeakingSettings(),
    val zebras: ZebraSettings = ZebraSettings(),
    val falseColor: FalseColorSettings = FalseColorSettings(),
    val guides: GuideSettings = GuideSettings(),
    val desqueeze: Desqueeze = Desqueeze.NONE
)

enum class HistogramMode { LUMA, RGB }

enum class WaveformMode { LUMA, RGB_OVERLAY, RGB_PARADE }

/**
 * Analysis meters. They are computed from a copy of the preview downsampled by [analysisDivisor] (4–8 is the useful
 * range: 1/8 of a 1080p preview is 240×135 = 32 400 samples), once every [updateEveryFrames] frames; drawing the last
 * result costs one small textured quad per scope every frame.
 */
data class ScopeSettings(
    val histogram: Boolean = false,
    val waveform: Boolean = false,
    val vectorscope: Boolean = false,
    val histogramMode: HistogramMode = HistogramMode.RGB,
    val waveformMode: WaveformMode = WaveformMode.LUMA,
    val analysisDivisor: Int = 8,
    val updateEveryFrames: Int = 2,
    /** Side of the vectorscope accumulation grid in cells (at most [MAX_VECTORSCOPE_SIZE]). */
    val vectorscopeSize: Int = 128,
    /** A histogram bar is full height at this many times the mean bin count. */
    val histogramScale: Float = 6f,
    val waveformGain: Float = 1f,
    val vectorscopeGain: Float = 1f,
    val backgroundOpacity: Float = 0.5f,
    /** Also read the histogram back to the CPU (asynchronously, through a PBO) for clipping statistics. */
    val histogramReadback: Boolean = false
) {
    init {
        require(analysisDivisor in 1..16) { "analysis divisor $analysisDivisor" }
        require(updateEveryFrames in 1..60) { "update interval $updateEveryFrames" }
        require(vectorscopeSize in 16..MAX_VECTORSCOPE_SIZE) { "vectorscope size $vectorscopeSize" }
        require(histogramScale > 0f && waveformGain > 0f && vectorscopeGain > 0f) { "scales must be positive" }
        require(backgroundOpacity in 0f..1f) { "background opacity $backgroundOpacity" }
    }

    val anyEnabled: Boolean get() = histogram || waveform || vectorscope

    /** Size of the analysis image for a [previewWidth]×[previewHeight] source, capped at [MAX_ANALYSIS_WIDTH] wide. */
    fun analysisSize(previewWidth: Int, previewHeight: Int): Pair<Int, Int> {
        var w = maxOf(1, previewWidth / analysisDivisor)
        var h = maxOf(1, previewHeight / analysisDivisor)
        if (w > MAX_ANALYSIS_WIDTH) {
            h = maxOf(1, (h.toLong() * MAX_ANALYSIS_WIDTH / w).toInt())
            w = MAX_ANALYSIS_WIDTH
        }
        return w to h
    }

    companion object {
        const val MAX_VECTORSCOPE_SIZE = 256
        const val MAX_ANALYSIS_WIDTH = 480
    }
}
