package com.qtekfun.ultimatecamera.core.gpu.meters

/**
 * Deterministic synthetic charts shared by the JVM reference tests and the on-device shader tests of the meters.
 * ARGB_8888, GL row order (row 0 at the bottom).
 */
object MeterTestCharts {
    const val OVERLAY_WIDTH = 192
    const val OVERLAY_HEIGHT = 108

    const val SCOPE_WIDTH = 64
    const val SCOPE_HEIGHT = 36

    /** The scope chart is uploaded upscaled by this factor so that the GPU downsample reproduces it exactly. */
    const val SCOPE_BLOCK = 4

    /**
     * Three bands of 36 rows: bottom a hue sweep fading to white (no sharp edges), middle a black/white checker of 8 px
     * (left half: peaking must fire on its edges) next to a gentle gradient (right half: it must not), top a grey ramp
     * 0..255 across the width (zebra and false-colour levels).
     */
    fun overlayChart(): IntArray = IntArray(OVERLAY_WIDTH * OVERLAY_HEIGHT) { i ->
        val x = i % OVERLAY_WIDTH
        val y = i / OVERLAY_WIDTH
        when (y / 36) {
            0 -> {
                val (r, g, b) = hue(x / (OVERLAY_WIDTH - 1f))
                val w = y / 35f * 0.6f
                rgb(r + (1 - r) * w, g + (1 - g) * w, b + (1 - b) * w)
            }
            1 -> if (x < OVERLAY_WIDTH / 2) {
                if ((x / 8 + y / 8) % 2 == 0) grey(16) else grey(235)
            } else {
                grey(70 + (x - OVERLAY_WIDTH / 2) / 2 + (y - 36) / 3)
            }
            else -> grey(x * 255 / (OVERLAY_WIDTH - 1))
        }
    }

    /**
     * The scope chart at analysis resolution: bottom 12 rows a full-saturation hue sweep, middle 12 rows the eight 75 %
     * colour bars (white, yellow, cyan, green, magenta, red, blue, black), top 12 rows a grey ramp.
     */
    fun scopeChart(): IntArray = IntArray(SCOPE_WIDTH * SCOPE_HEIGHT) { i ->
        val x = i % SCOPE_WIDTH
        val y = i / SCOPE_WIDTH
        when (y / 12) {
            0 -> hue(x / (SCOPE_WIDTH - 1f)).let { (r, g, b) -> rgb(r, g, b) }
            1 -> BARS_75[x * BARS_75.size / SCOPE_WIDTH]
            else -> grey(x * 255 / (SCOPE_WIDTH - 1))
        }
    }

    /** Repeats every pixel of a [width]×[height] image in a [factor]×[factor] block. */
    fun blockUpscale(pixels: IntArray, width: Int, height: Int, factor: Int): IntArray = IntArray(width * factor * height * factor) { i ->
        val x = (i % (width * factor)) / factor
        val y = (i / (width * factor)) / factor
        pixels[y * width + x]
    }

    private val BARS_75 = intArrayOf(
        rgb8(191, 191, 191),
        rgb8(191, 191, 0),
        rgb8(0, 191, 191),
        rgb8(0, 191, 0),
        rgb8(191, 0, 191),
        rgb8(191, 0, 0),
        rgb8(0, 0, 191),
        rgb8(0, 0, 0)
    )

    private fun hue(t: Float): Triple<Float, Float, Float> {
        val h = t * 6f
        val f = h - h.toInt()
        return when (h.toInt() % 6) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(1f - f, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, 1f - f, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, 1f - f)
        }
    }

    private fun grey(v: Int) = rgb8(v, v, v)

    private fun rgb(r: Float, g: Float, b: Float) = rgb8(Rgb8.to8(r), Rgb8.to8(g), Rgb8.to8(b))

    private fun rgb8(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Overlay settings exercised by the reference tests on the JVM and on the device (same instances on both sides). */
object MeterReferenceSettings {
    val peaking = MonitorOverlaySettings(peaking = PeakingSettings(enabled = true, threshold = 0.12f))

    val zebras = MonitorOverlaySettings(
        zebras = ZebraSettings(
            primary = ZebraBand(enabled = true, minPercent = 95f, maxPercent = 110f, color = 0xFF000000.toInt()),
            secondary = ZebraBand(enabled = true, minPercent = 65f, maxPercent = 75f, color = 0xFFFFFFFF.toInt()),
            stripeWidthPx = 4,
            animated = true,
            speedPxPerFrame = 1
        )
    )

    val falseColor = MonitorOverlaySettings(falseColor = FalseColorSettings(enabled = true))

    val guides = MonitorOverlaySettings(
        guides = GuideSettings(
            thirds = true,
            centre = true,
            safeAreas = listOf(GuideSettings.ACTION_SAFE, GuideSettings.TITLE_SAFE),
            frameLineAspect = GuideSettings.ASPECT_2_39,
            matteOpacity = 0.5f,
            lineWidthPx = 2
        )
    )

    val all = MonitorOverlaySettings(
        peaking = peaking.peaking,
        zebras = zebras.zebras,
        falseColor = falseColor.falseColor,
        guides = guides.guides
    )
}
