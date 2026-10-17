package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane

/**
 * One depth update of the live portrait preview (D87): the inverse depth in the sensor frame (row 0 at the top), the
 * focus picked on it ([Bokeh.focusAt] at the tapped point) and the aperture. Immutable: handed from the depth worker
 * to the GL thread as a whole.
 */
class LiveDepth(val inverse: Plane, val focus: Float, val fNumber: Float) {
    /** The focus the live shader gets: [Bokeh.INVALID] (no blur anywhere) when the aperture slider is off. */
    val shaderFocus: Float get() = if (fNumber > 0f) focus else Bokeh.INVALID

    /** The shader's CoC per dioptre on a copy [width] pixels wide ([Bokeh.cocScale]). */
    fun shaderScale(width: Int): Float = Bokeh.cocScale(fNumber, width)
}

/**
 * How big and how often the live blurred preview runs (D87, performance on modest phones first): the camera frame is
 * blurred on a copy [width] x [height] (sensor frame, the preview's aspect) at most once every [blurEvery] preview
 * frames; the camera delivers depth with one request in [depthEvery]; the depth worker takes at most one frame every
 * [minDepthIntervalNanos] and drops the rest.
 */
data class LivePortraitPlan(val width: Int, val height: Int, val blurEvery: Int, val depthEvery: Int, val minDepthIntervalNanos: Long, val maxBlurEvery: Int) {
    /** Largest blur radius on the live copy: the capture's bound scaled to its width, so both look alike. */
    val maxRadius: Float get() = DepthBlur.MAX_RADIUS * width / CAPTURE_WIDTH

    companion object {
        /** The reduced width the capture blur runs at (`PortraitProcessor.REDUCED_WIDTH`). */
        const val CAPTURE_WIDTH = 1024
        private const val WIDTH = 480
        private const val MODEST_WIDTH = 320
        private const val NANOS_PER_MS = 1_000_000L

        /** The plan for a [previewWidth] x [previewHeight] sensor-frame preview; [modest] phones get less of everything. */
        fun of(previewWidth: Int, previewHeight: Int, modest: Boolean): LivePortraitPlan {
            require(previewWidth > 0 && previewHeight > 0) { "preview size" }
            val width = minOf(if (modest) MODEST_WIDTH else WIDTH, previewWidth).coerceAtLeast(2) and 1.inv()
            val height = ((width.toLong() * previewHeight + previewWidth / 2) / previewWidth).toInt().coerceAtLeast(2) and 1.inv()
            return if (modest) {
                LivePortraitPlan(width, height, blurEvery = 3, depthEvery = 4, minDepthIntervalNanos = 150 * NANOS_PER_MS, maxBlurEvery = 6)
            } else {
                LivePortraitPlan(width, height, blurEvery = 2, depthEvery = 3, minDepthIntervalNanos = 100 * NANOS_PER_MS, maxBlurEvery = 4)
            }
        }
    }
}

/**
 * Which preview frames run the blur pass (D87): one in [stride], starting at the plan's rate, the others show the
 * last blurred picture. When the frames that blurred take longer than [budgetNanos] on average the stride grows (up
 * to [maxStride]): the viewfinder never stalls, the blur just refreshes less often; after [RECOVERY_FRAMES] cheap
 * blurred frames in a row (under half the budget) it shrinks back towards [baseStride]. GL thread only; no allocation.
 */
class LivePortraitPacer(private val baseStride: Int, private val maxStride: Int, private val budgetNanos: Long) {
    init {
        require(baseStride >= 1 && maxStride >= baseStride && budgetNanos > 0) { "pacer bounds" }
    }

    var stride: Int = baseStride
        private set

    private var frame = 0
    private var average = 0.0
    private var samples = 0
    private var cheap = 0

    /** True when the next preview frame should run the blur. */
    fun next(): Boolean = frame++ % stride == 0

    /** The render time of a frame; only frames that ran the blur ([blurred]) count. */
    fun record(blurred: Boolean, renderNanos: Long) {
        if (!blurred) return
        samples++
        average += (renderNanos - average) / minOf(samples, WINDOW)
        if (samples >= WINDOW && average > budgetNanos && stride < maxStride) {
            stride++
            reset()
        } else if (renderNanos * 2 < budgetNanos) {
            if (++cheap >= RECOVERY_FRAMES && stride > baseStride) {
                stride--
                reset()
            }
        } else {
            cheap = 0
        }
    }

    private fun reset() {
        average = 0.0
        samples = 0
        cheap = 0
        frame = 0
    }

    companion object {
        /** Blurred frames averaged before the stride may grow. */
        const val WINDOW = 8

        /** Cheap blurred frames in a row before the stride shrinks. */
        const val RECOVERY_FRAMES = 60

        /** The pacer for [plan] at a camera rate of [fps]: the blurred frame may use 60 % of the frame interval. */
        fun forPlan(plan: LivePortraitPlan, fps: Int): LivePortraitPacer =
            LivePortraitPacer(plan.blurEvery, plan.maxBlurEvery, (NANOS_PER_SECOND * BUDGET_PERCENT / 100) / fps.coerceAtLeast(1))

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BUDGET_PERCENT = 60
    }
}

/**
 * Admits live depth frames to the single depth worker (D87): a frame is taken only when the worker is idle and at
 * least [minIntervalNanos] passed since the last one taken; every other frame is dropped (counted in [dropped]), so
 * depth work can never queue up behind the camera. Thread-safe.
 */
class LiveDepthGate(private val minIntervalNanos: Long) {
    private var busy = false
    private var last = Long.MIN_VALUE

    @Volatile var dropped = 0
        private set

    /** True when the frame arriving at [nowNanos] goes to the worker, which must then call [release]. */
    @Synchronized
    fun tryAcquire(nowNanos: Long): Boolean {
        if (busy || (last != Long.MIN_VALUE && nowNanos - last < minIntervalNanos)) {
            dropped++
            return false
        }
        busy = true
        last = nowNanos
        return true
    }

    @Synchronized
    fun release() {
        busy = false
    }
}
