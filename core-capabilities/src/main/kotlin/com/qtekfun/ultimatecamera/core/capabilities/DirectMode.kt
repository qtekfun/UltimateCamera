package com.qtekfun.ultimatecamera.core.capabilities

/**
 * Experimental direct mode (D37, D71): the camera session outputs straight into the encoder's input surface plus a
 * small preview stream for the viewfinder; there is no GL pass on the recording (no LUT baking, no overlays, no monitor
 * stream from the encoder), and the file is tagged as the camera delivers (HLG for a 10-bit stream). It exists for 8K,
 * where the GL pass alone costs more than a frame (Pixel 8 spike: 8K30 accepted but 21 fps, GL p95 75 ms).
 *
 * In the catalogue a direct configuration is a variant ([VideoConfig.vendorMode] = [MODE]): its results never stand
 * for the GL configuration of the same size, nor the other way round.
 */
object DirectMode {
    /** [VideoConfig.vendorMode] of a direct configuration. */
    const val MODE = "direct"

    /** Widest preview stream next to the encoder output (a guaranteed RECORD + PREVIEW combination stays ≤ 1080p). */
    const val MAX_PREVIEW_WIDTH = 1920

    /** Height from which a profile always records direct: 8K (4320 lines). */
    const val ALWAYS_DIRECT_HEIGHT = 4320

    /** What the auto-configuration tries as direct variants, cheapest first: 8K30 and 8K60, 10-bit, no LUT. */
    val RUNGS: List<LadderRung> = listOf(
        LadderRung(7680, 4320, 30, tenBit = true, lut = LutMode.NONE),
        LadderRung(7680, 4320, 60, tenBit = true, lut = LutMode.NONE)
    )

    /** Whether a recording of [height] lines (or a profile [flagged] direct) skips the GL pass. */
    fun applies(height: Int, flagged: Boolean): Boolean = flagged || height >= ALWAYS_DIRECT_HEIGHT

    fun isDirect(config: VideoConfig): Boolean = config.vendorMode == MODE

    /**
     * The preview stream next to a [width]×[height] recording: the same aspect ratio, at most [MAX_PREVIEW_WIDTH] wide,
     * even sides (YUV/PRIVATE buffers need them).
     */
    fun previewSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        if (width <= MAX_PREVIEW_WIDTH) return even(width) to even(height)
        val h = (height.toLong() * MAX_PREVIEW_WIDTH / width).toInt()
        return MAX_PREVIEW_WIDTH to even(h)
    }

    private fun even(v: Int) = maxOf(2, v and 1.inv())
}
