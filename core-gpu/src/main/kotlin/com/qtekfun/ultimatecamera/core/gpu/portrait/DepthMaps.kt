package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import kotlin.math.abs

/** Reads the camera's DEPTH16 samples (D73): 13 bits of range in millimetres, 3 bits of confidence. */
object Depth16 {
    private const val RANGE_MASK = 0x1FFF
    private const val CONFIDENCE_SHIFT = 13
    private const val CONFIDENCE_MASK = 0x7
    private const val CONFIDENCE_LEVELS = 7f
    private const val MM_PER_M = 1000f

    /** Samples below this confidence count as no depth. */
    const val MIN_CONFIDENCE = 0.25f

    /** Confidence of one sample, 0..1 (the encoding of `ImageFormat.DEPTH16`: 0 means 100 %, 1 means 0 %). */
    fun confidence(sample: Int): Float {
        val c = sample shr CONFIDENCE_SHIFT and CONFIDENCE_MASK
        return if (c == 0) 1f else (c - 1) / CONFIDENCE_LEVELS
    }

    /** Range of one sample in millimetres (0 = no measurement). */
    fun rangeMm(sample: Int): Int = sample and RANGE_MASK

    /**
     * The inverse depth (dioptres) of a DEPTH16 image of [width] x [height] whose rows start every [rowStride] samples
     * in [samples]; samples without range or with too little confidence are [Bokeh.INVALID].
     */
    fun inverseDepth(samples: ShortArray, width: Int, height: Int, rowStride: Int = width): Plane {
        val out = Plane(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val s = samples[y * rowStride + x].toInt() and 0xFFFF
                val mm = rangeMm(s)
                out[x, y] = if (mm == 0 || confidence(s) < MIN_CONFIDENCE) Bokeh.INVALID else MM_PER_M / mm
            }
        }
        return out
    }
}

/**
 * Disparity of a stereo pair by block matching (D73, experimental): for every pixel of [main], the offset (along x or
 * along y, up to [maxDisparity] pixels: the baseline direction of a phone's cameras depends on how it is held) that
 * minimises the sum of absolute differences of a block of side `2 · blockRadius + 1` against [second]. Box sums use an
 * integral image, so the cost is `width · height · offsets`, cheap at the reduced size it runs at. Flat regions, where
 * no offset is clearly better, get [Bokeh.INVALID].
 */
object StereoDepth {
    /** The best cost must be this much below the cost at the farthest offset to count (texture check). */
    private const val MIN_CONTRAST = 0.02f

    /** Disparity in pixels (non-negative) per pixel of [main]; both planes the same size, luma 0..1. */
    fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int = 3): Plane {
        require(main.width == second.width && main.height == second.height) { "stereo planes differ in size" }
        val w = main.width
        val h = main.height
        val best = FloatArray(w * h) { Float.MAX_VALUE }
        val worst = FloatArray(w * h)
        val disparity = Plane(w, h)
        val offsets = buildList {
            add(0 to 0)
            for (d in 1..maxDisparity) {
                add(d to 0)
                add(-d to 0)
                add(0 to d)
                add(0 to -d)
            }
        }
        val diff = FloatArray(w * h)
        val area = ((2 * blockRadius + 1) * (2 * blockRadius + 1)).toFloat()
        for ((dx, dy) in offsets) {
            for (y in 0 until h) {
                val sy = (y + dy).coerceIn(0, h - 1)
                for (x in 0 until w) diff[y * w + x] = abs(main[x, y] - second[(x + dx).coerceIn(0, w - 1), sy])
            }
            val sums = boxSums(diff, w, h, blockRadius)
            val d = maxOf(abs(dx), abs(dy)).toFloat()
            for (i in 0 until w * h) {
                val cost = sums[i] / area
                if (cost < best[i]) {
                    best[i] = cost
                    disparity.data[i] = d
                }
                if (cost > worst[i]) worst[i] = cost
            }
        }
        for (i in 0 until w * h) if (worst[i] - best[i] < MIN_CONTRAST) disparity.data[i] = Bokeh.INVALID
        return disparity
    }

    /**
     * Inverse depth from [disparity]: disparity is proportional to inverse depth, with an unknown scale on an
     * uncalibrated pair, so [maxDisparity] maps to [nearInverse] dioptres (0.5 m by default).
     */
    fun inverseDepth(disparity: Plane, maxDisparity: Int, nearInverse: Float = NEAR_INVERSE): Plane {
        val out = Plane(disparity.width, disparity.height)
        for (i in out.data.indices) {
            val d = disparity.data[i]
            out.data[i] = if (d <= Bokeh.INVALID) Bokeh.INVALID else d / maxDisparity * nearInverse
        }
        return out
    }

    /** Sum of each pixel's (2r+1)² block, clamped at the borders, through an integral image. */
    private fun boxSums(values: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += values[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r + 1).coerceAtMost(h)
            for (x in 0 until w) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r + 1).coerceAtMost(w)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                // Normalise clamped border blocks to the full block area.
                out[y * w + x] = (sum * ((2 * r + 1) * (2 * r + 1)) / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }

    /** Default inverse depth of the largest disparity. */
    const val NEAR_INVERSE = 2f
}

/** The depth map for editors (D73): near is white, far is black, no depth is black; one byte per pixel. */
object DepthImage {
    fun grey(depth: Plane): ByteArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in depth.data) {
            if (v <= Bokeh.INVALID) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        return ByteArray(depth.data.size) { i ->
            val v = depth.data[i]
            if (v <= Bokeh.INVALID) 0 else ((v - lo) / span * MAX_GREY + 0.5f).toInt().coerceIn(0, MAX_GREY).toByte()
        }
    }

    private const val MAX_GREY = 255
}
