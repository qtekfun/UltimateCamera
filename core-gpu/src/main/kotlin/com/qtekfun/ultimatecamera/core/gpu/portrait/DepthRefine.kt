package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.GuidedBase
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Subject segmentation where the depth is weak (D88): a classical, model-free refinement of a sparse or holey inverse
 * depth map with the photo as guide, used only when the map's coverage is between [MIN_COVERAGE] and [REFINE_BELOW].
 *
 * 1. **Edge-aware fill** ([fill]): every hole takes the depth of the measured pixel nearest to it along the photo, where
 *    a step costs one pixel plus [EDGE_COST] per unit of colour difference (sum over R, G, B): a geodesic distance,
 *    Dijkstra on the grid. A hole is therefore filled from its own side of a colour edge, never across it, and holes
 *    farther than the reach from any measurement stay without depth.
 * 2. **Subject mask from colour and depth** ([subjectMask]): the pixels whose depth lies within the focus band form a
 *    mask; well inside it the subject is sure, well outside it the background is sure, and every pixel of the band in
 *    between (where depth bleeds over outlines) goes to the class whose local mean colour is closer to its own (a
 *    trimap with local colour models). Subject pixels out of the focus band are pulled to the focus; band pixels the
 *    colours call background lose their depth and are filled again from the background along the photo.
 *
 * With too little depth (below [MIN_COVERAGE]) or no focus the map is returned as it is: no measurement, no blur (we
 * never fake the blur). A well-covered map (from [REFINE_BELOW]) is returned as measured too.
 */
object DepthRefine {
    /** Coverage from which the measured map is used as it is. */
    const val REFINE_BELOW = 0.9f

    /** Coverage below which nothing is reliable enough to refine: the map stays as it is (holes are not blurred). */
    const val MIN_COVERAGE = 0.2f

    /** Geodesic cost of a colour step of 1 in one channel (black to white costs three times this), in pixels. */
    const val EDGE_COST = 30f

    /** Smallest focus band in dioptres (a noisy subject must still be one subject). */
    const val MIN_BAND = 0.08f

    /** What [refine] did, for the render log. */
    enum class Mode(val label: String) {
        MEASURED("measured"),
        REFINED("refined"),
        TOO_SPARSE("too sparse")
    }

    /** The map to blur with, what was done and the measured [coverage] (0..1). */
    class Result(val depth: Plane, val mode: Mode, val coverage: Float) {
        /** For the log: "refined, coverage 45 %". */
        fun describe(): String = "${mode.label}, coverage ${(coverage * PERCENT).roundToInt()} %"
    }

    /** Fraction of pixels with depth. */
    fun coverage(depth: Plane): Float {
        if (depth.data.isEmpty()) return 0f
        var n = 0
        for (v in depth.data) if (v > Bokeh.INVALID) n++
        return n.toFloat() / depth.data.size
    }

    /**
     * Refines [depth] (inverse depth, [Bokeh.INVALID] = none) with [guide] (the photo at the depth's size) around
     * [focus] (inverse depth) for an aperture [fNumber]; see the class comment for when it runs.
     */
    fun refine(depth: Plane, guide: RgbTile, focus: Float, fNumber: Float): Result {
        require(depth.width == guide.width && depth.height == guide.height) { "depth and guide sizes differ" }
        val c = coverage(depth)
        return when {
            c >= REFINE_BELOW -> Result(depth, Mode.MEASURED, c)
            c < MIN_COVERAGE || focus <= Bokeh.INVALID -> Result(depth, Mode.TOO_SPARSE, c)
            else -> {
                val reach = reach(depth.width, depth.height)
                val filled = fill(depth, guide, reach)
                Result(fill(snap(filled, guide, focus, band(fNumber, depth.width)), guide, reach), Mode.REFINED, c)
            }
        }
    }

    /** How far (geodesic pixels) a fill may carry a measurement: an eighth of the map's long side. */
    fun reach(width: Int, height: Int): Float = maxOf(width, height) / REACH_DIVISOR

    /**
     * The focus band in dioptres for [fNumber] on a map [width] pixels wide: the depth gap that blurs by less than
     * [BAND_PX] map pixels, at least [MIN_BAND] (f/16 keeps more in focus than f/1.4, as a lens would).
     */
    fun band(fNumber: Float, width: Int): Float {
        val scale = Bokeh.cocScale(fNumber, width)
        return if (scale <= 0f) Float.MAX_VALUE else maxOf(MIN_BAND, BAND_PX / scale)
    }

    /**
     * The edge-aware fill: holes take the depth of the geodesically nearest measured pixel ([EDGE_COST] per unit of
     * [guide] difference); holes beyond [reach] stay [Bokeh.INVALID]. Measured pixels are kept as they are.
     */
    fun fill(depth: Plane, guide: RgbTile, reach: Float): Plane {
        val w = depth.width
        val h = depth.height
        val out = Plane(w, h, depth.data.copyOf())
        val cost = FloatArray(w * h) { if (depth.data[it] > Bokeh.INVALID) 0f else Float.MAX_VALUE }
        val heap = CostHeap(w * h)
        for (i in cost.indices) if (cost[i] == 0f) heap.push(i, 0f)
        val r = guide.r.data
        val g = guide.g.data
        val b = guide.b.data
        while (heap.size > 0) {
            val c = heap.topCost()
            val i = heap.pop()
            if (c > cost[i]) continue // A stale entry: the pixel was reached more cheaply since.
            val x = i % w
            val y = i / w
            for (n in 0 until NEIGHBOURS) {
                val nx = x + DX[n]
                val ny = y + DY[n]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                val step = abs(r[j] - r[i]) + abs(g[j] - g[i]) + abs(b[j] - b[i])
                val next = c + 1f + EDGE_COST * step
                if (next < cost[j] && next <= reach) {
                    cost[j] = next
                    out.data[j] = out.data[i]
                    heap.push(j, next)
                }
            }
        }
        return out
    }

    /**
     * The subject snapped to the photo ([subjectMask]): subject pixels go to [focus] when outside the [band], background
     * pixels inside the band lose their depth (to be filled again from the background).
     */
    fun snap(depth: Plane, guide: RgbTile, focus: Float, band: Float): Plane {
        val subject = subjectMask(depth, guide, focus, band)
        val out = Plane(depth.width, depth.height)
        for (i in out.data.indices) {
            val d = depth.data[i]
            val inBand = d > Bokeh.INVALID && abs(d - focus) <= band
            out.data[i] = when {
                subject[i] && !inBand -> focus
                !subject[i] && inBand -> Bokeh.INVALID
                else -> d
            }
        }
        return out
    }

    /**
     * The subject (true) by colour and depth: in-band pixels ([band] around [focus]) at least [margin] pixels inside the
     * band mask are sure subject, pixels at least [margin] outside it sure background; the pixels between take the
     * class whose mean colour over the sure pixels within twice the margin is closer to theirs (squared RGB distance).
     * Where one class has no sure pixel nearby the depth decides.
     */
    fun subjectMask(depth: Plane, guide: RgbTile, focus: Float, band: Float, margin: Int = margin(depth.width, depth.height)): BooleanArray {
        val w = depth.width
        val h = depth.height
        val n = w * h
        val mask = Plane(
            w,
            h,
            FloatArray(n) {
                val d = depth.data[it]
                if (d > Bokeh.INVALID && abs(d - focus) <= band) 1f else 0f
            }
        )
        val near = GuidedBase.boxMean(mask, margin)
        val sureSubject = Plane(w, h, FloatArray(n) { if (near.data[it] >= 1f - EPS) 1f else 0f })
        val sureBackground = Plane(w, h, FloatArray(n) { if (near.data[it] <= EPS) 1f else 0f })
        val window = 2 * margin
        val subjectShare = GuidedBase.boxMean(sureSubject, window)
        val backgroundShare = GuidedBase.boxMean(sureBackground, window)
        val subjectMeans = guide.channels.map { c -> GuidedBase.boxMean(Plane(w, h, FloatArray(n) { c.data[it] * sureSubject.data[it] }), window) }
        val backgroundMeans = guide.channels.map { c -> GuidedBase.boxMean(Plane(w, h, FloatArray(n) { c.data[it] * sureBackground.data[it] }), window) }
        return BooleanArray(n) { i ->
            when {
                sureSubject.data[i] > 0f -> true
                sureBackground.data[i] > 0f -> false
                subjectShare.data[i] <= EPS || backgroundShare.data[i] <= EPS -> mask.data[i] > 0f
                else -> {
                    var toSubject = 0f
                    var toBackground = 0f
                    for (k in guide.channels.indices) {
                        val v = guide.channels[k].data[i]
                        val ds = v - subjectMeans[k].data[i] / subjectShare.data[i]
                        val db = v - backgroundMeans[k].data[i] / backgroundShare.data[i]
                        toSubject += ds * ds
                        toBackground += db * db
                    }
                    toSubject < toBackground
                }
            }
        }
    }

    /** The width of the uncertain band around the depth's outline: a 50th of the map's long side, at least 2 pixels. */
    fun margin(width: Int, height: Int): Int = (maxOf(width, height) / MARGIN_DIVISOR).coerceAtLeast(MIN_MARGIN)

    private const val PERCENT = 100f
    private const val EPS = 1e-4f
    private const val REACH_DIVISOR = 8f
    private const val BAND_PX = 1.5f
    private const val MARGIN_DIVISOR = 50
    private const val MIN_MARGIN = 2
    private const val NEIGHBOURS = 4
    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)
}

/** A binary min-heap of (pixel, cost) in primitive arrays (no boxing on a 300 k-pixel fill); grows as needed. */
internal class CostHeap(capacity: Int) {
    private var items = IntArray(capacity.coerceAtLeast(1))
    private var costs = FloatArray(capacity.coerceAtLeast(1))
    var size = 0
        private set

    fun push(item: Int, cost: Float) {
        if (size == items.size) {
            items = items.copyOf(size * 2)
            costs = costs.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val parent = (i - 1) / 2
            if (costs[parent] <= cost) break
            items[i] = items[parent]
            costs[i] = costs[parent]
            i = parent
        }
        items[i] = item
        costs[i] = cost
    }

    fun topCost(): Float = costs[0]

    /** Removes and returns the cheapest item. */
    fun pop(): Int {
        val top = items[0]
        size--
        if (size > 0) {
            val item = items[size]
            val cost = costs[size]
            var i = 0
            var child = cheaperChild(0)
            while (child >= 0 && costs[child] < cost) {
                items[i] = items[child]
                costs[i] = costs[child]
                i = child
                child = cheaperChild(i)
            }
            items[i] = item
            costs[i] = cost
        }
        return top
    }

    /** The cheaper child of [i], or −1 for a leaf. */
    private fun cheaperChild(i: Int): Int {
        val left = 2 * i + 1
        return when {
            left >= size -> -1
            left + 1 < size && costs[left + 1] < costs[left] -> left + 1
            else -> left
        }
    }
}
