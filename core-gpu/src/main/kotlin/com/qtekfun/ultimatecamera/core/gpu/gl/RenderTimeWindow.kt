package com.qtekfun.ultimatecamera.core.gpu.gl

/**
 * The last [capacity] frame render times, for the status bar's p95. [record] runs on the render thread once per frame
 * and never allocates; [percentile] runs elsewhere at a few hertz and copies into a scratch array allocated once.
 *
 * Reads race with writes on purpose (no lock on the render thread): a percentile may mix one frame from before and one
 * from after, which does not matter for a statistic shown at 2 Hz.
 */
class RenderTimeWindow(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val samples = LongArray(capacity)
    private val scratch = LongArray(capacity)

    @Volatile private var count = 0
    private var next = 0

    /** Adds one frame's render time (render thread only). */
    fun record(nanos: Long) {
        samples[next] = nanos
        next = if (next + 1 == capacity) 0 else next + 1
        if (count < capacity) count++
    }

    /** Forgets every sample (e.g. at the start of a take). Same thread as [record]. */
    fun clear() {
        count = 0
        next = 0
    }

    /** The [p]-th percentile (0..1) of the window in nanoseconds, nearest-rank; 0 when empty. Not for the render thread. */
    @Synchronized
    fun percentile(p: Double): Long {
        val n = count
        if (n == 0) return 0
        System.arraycopy(samples, 0, scratch, 0, n)
        scratch.sort(0, n)
        val rank = kotlin.math.ceil(p.coerceIn(0.0, 1.0) * n).toInt().coerceIn(1, n)
        return scratch[rank - 1]
    }

    companion object {
        /** Two seconds at 60 fps. */
        const val DEFAULT_CAPACITY = 120
    }
}
