package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import kotlin.math.abs

/**
 * Circle-of-confusion math of the portrait blur (D73). Depth is carried as inverse depth in dioptres (1/m), where the
 * thin-lens blur is linear: a point at inverse depth `d` seen by a lens of focal length `f` at f-number `N` focused at
 * inverse depth `d_f` blurs to a disc of diameter `f² / N · |d − d_f|` on the sensor (for subjects far beyond `f`).
 * The look is that of a 50 mm lens on a 36 mm-wide frame, whatever phone took the photo, so f/1.4…f/16 mean what a
 * photographer expects. A value at or below [INVALID] means "no depth here" and is never blurred (no fake blur).
 */
object Bokeh {
    /** The aperture slider's stops; position 0 of the slider is "off". */
    val STOPS: List<Float> = listOf(1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)

    /** Virtual lens and frame: 50 mm on a 36 mm-wide frame. */
    const val VIRTUAL_FOCAL_M = 0.05f
    const val FRAME_WIDTH_M = 0.036f

    /** Marks a pixel without depth. */
    const val INVALID = -1f

    /** The f-number of slider position [position] (0 = off → 0). */
    fun fNumber(position: Int): Float = if (position <= 0) 0f else STOPS[(position - 1).coerceAtMost(STOPS.size - 1)]

    /**
     * Diameter of the circle of confusion in pixels of an image [widthPx] wide, for a point at inverse depth [inverse]
     * with focus at [focusInverse] and f-number [fNumber]; 0 when the blur is off ([fNumber] ≤ 0) or there is no depth.
     */
    fun cocPx(inverse: Float, focusInverse: Float, fNumber: Float, widthPx: Int): Float {
        if (inverse <= INVALID || focusInverse <= INVALID) return 0f
        return cocScale(fNumber, widthPx) * abs(inverse - focusInverse)
    }

    /**
     * CoC diameter in pixels of an image [widthPx] wide per dioptre away from the focus, at [fNumber] (0 when off): the
     * one factor the live preview's shader multiplies by `|d − d_focus|` ([cocPx] = this × the gap).
     */
    fun cocScale(fNumber: Float, widthPx: Int): Float = if (fNumber <= 0f) 0f else VIRTUAL_FOCAL_M * VIRTUAL_FOCAL_M / fNumber / FRAME_WIDTH_M * widthPx

    /**
     * The focus for a tap at the normalised point ([nx], [ny], sensor frame): the median depth in a small window
     * ([focusInverseDepth]); when the window has no depth (a hole of the depth map, e.g. a flat wall in stereo), the
     * median of a window an eighth of the map wide, so a tap never leaves the photo without a focus.
     */
    fun focusAt(depth: Plane, nx: Float, ny: Float): Float {
        val near = focusInverseDepth(depth, nx, ny)
        return if (near > INVALID) near else focusInverseDepth(depth, nx, ny, radius = (depth.width / WIDE_FOCUS_DIVISOR).coerceAtLeast(FOCUS_RADIUS + 1))
    }

    /**
     * The focus as inverse depth: the median of the valid depth around the normalised point ([nx], [ny] in 0..1) in a
     * window of [radius] depth pixels; [INVALID] when the window has no depth.
     */
    fun focusInverseDepth(depth: Plane, nx: Float, ny: Float, radius: Int = FOCUS_RADIUS): Float {
        val cx = (nx * (depth.width - 1)).toInt().coerceIn(0, depth.width - 1)
        val cy = (ny * (depth.height - 1)).toInt().coerceIn(0, depth.height - 1)
        val values = ArrayList<Float>()
        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(depth.height - 1)) {
            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(depth.width - 1)) {
                val v = depth[x, y]
                if (v > INVALID) values += v
            }
        }
        if (values.isEmpty()) return INVALID
        values.sort()
        return values[values.size / 2]
    }

    private const val FOCUS_RADIUS = 3
    private const val WIDE_FOCUS_DIVISOR = 8
}
