package com.qtekfun.ultimatecamera.core.gpu.meters

/** Summary of the last measurements of one pass, in milliseconds. */
data class CostSummary(val samples: Int, val meanMs: Float, val p95Ms: Float, val maxMs: Float)

/**
 * Ring of the last [capacity] cost samples of one pass. [add] is allocation-free (it runs on the render thread);
 * [summary] sorts a preallocated copy and allocates only the returned summary, so call it rarely (a report, a probe).
 */
class CostHistory(val capacity: Int = 120) {
    private val samples = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var next = 0
    var count = 0
        private set

    fun add(ms: Float) {
        samples[next] = ms
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        next = 0
        count = 0
    }

    /** Mean, nearest-rank 95th percentile and maximum of the samples held; null when there are none. */
    fun summary(): CostSummary? {
        if (count == 0) return null
        samples.copyInto(scratch, 0, 0, count)
        scratch.sort(0, count)
        var sum = 0.0
        for (i in 0 until count) sum += scratch[i]
        val rank = ((count * 95 + 99) / 100).coerceIn(1, count)
        return CostSummary(count, (sum / count).toFloat(), scratch[rank - 1], scratch[count - 1])
    }
}

/** The passes the meter renderers time, as sections of a `GpuTimer` (index = ordinal). */
enum class MeterPass {
    OVERLAY,
    GUIDES,
    SCOPE_DOWNSAMPLE,
    HISTOGRAM,
    WAVEFORM,
    VECTORSCOPE,
    SCOPE_DISPLAY
}
