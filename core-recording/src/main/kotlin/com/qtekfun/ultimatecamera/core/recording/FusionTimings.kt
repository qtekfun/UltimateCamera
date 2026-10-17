package com.qtekfun.ultimatecamera.core.recording

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Time per stage of one HDR fusion, in ms. Stages called from several threads (rotate) add up their threads' time, so
 * they can exceed the wall time of the stage that contains them. Logged as one `FUSION_STAGE` line for the benchmark.
 */
class FusionTimings {
    private val stages = ConcurrentHashMap<String, AtomicLong>()

    /** "gpu" or "cpu" (after a GPU failure). */
    @Volatile var path = "gpu"

    /** The frames' shifts as found. */
    @Volatile var align = ""

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(stage, (System.nanoTime() - start) / NANOS_PER_MS)
        }
    }

    fun add(stage: String, ms: Long) {
        stages.computeIfAbsent(stage) { AtomicLong() }.addAndGet(ms)
    }

    operator fun get(stage: String): Long = stages[stage]?.get() ?: 0L

    override fun toString(): String = "FUSION_STAGE path=$path " + ORDER.joinToString(" ") { "$it=${get(it)}" } + " shifts=$align"

    companion object {
        const val NANOS_PER_MS = 1_000_000L

        /** The stages in pipeline order (the lead's list plus GL setup and upload). */
        val ORDER = listOf("read", "gl", "upload", "align", "downscale", "fuse", "upsample", "gainmap", "jpeg", "rotate")
    }
}
