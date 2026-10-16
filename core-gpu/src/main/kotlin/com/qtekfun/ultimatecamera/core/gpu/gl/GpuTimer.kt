package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.meters.CostHistory
import com.qtekfun.ultimatecamera.core.gpu.meters.CostSummary

/**
 * Cost-measurement hook for the spike and the auto-configuration: how long each pass (a section, e.g. a
 * `MeterPass.ordinal`) takes on this phone's GPU.
 *
 * - [Mode.GPU_TIMER_QUERY] (with `GL_EXT_disjoint_timer_query`): [begin]/[end] wrap the pass in a `GL_TIME_ELAPSED_EXT`
 *   query from a ring of [QUERY_SLOTS]; [poll] collects finished results without waiting and drops them when the
 *   driver reports a disjoint event (frequency change, context loss). If the next slot is still in flight the
 *   measurement is skipped, never waited for. Sections cannot nest (GL allows one active elapsed-time query): a nested
 *   [begin] is ignored.
 * - [Mode.CPU_FINISH], only when asked for with `cpuFallback` and no timer query exists: `glFinish` + wall clock around
 *   the pass. It stalls the pipeline, so it is for debug probes, never while recording.
 * - [Mode.OFF] otherwise: every call is a no-op.
 *
 * Create, use and [release] it on the GL thread. Per frame it allocates nothing.
 */
class GpuTimer(sections: Int, cpuFallback: Boolean = false, history: Int = 120, capabilities: GlCapabilities = GlCapabilities.current()) {
    enum class Mode { GPU_TIMER_QUERY, CPU_FINISH, OFF }

    val mode: Mode = when {
        capabilities.timerQuery -> Mode.GPU_TIMER_QUERY
        cpuFallback -> Mode.CPU_FINISH
        else -> Mode.OFF
    }

    private val histories = Array(sections) { CostHistory(history) }
    private val queries = IntArray(QUERY_SLOTS)
    private val slotSection = IntArray(QUERY_SLOTS)
    private val pending = BooleanArray(QUERY_SLOTS)
    private val value = IntArray(1)
    private var nextSlot = 0
    private var activeSlot = -1
    private var activeSection = -1
    private var cpuStart = 0L

    /** Measurements dropped because the query ring was full or a disjoint event invalidated them. */
    var skipped = 0
        private set

    init {
        if (mode == Mode.GPU_TIMER_QUERY) GLES30.glGenQueries(QUERY_SLOTS, queries, 0)
    }

    fun begin(section: Int) {
        if (activeSection != -1) return
        when (mode) {
            Mode.GPU_TIMER_QUERY -> {
                if (pending[nextSlot]) {
                    skipped++
                    return
                }
                GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[nextSlot])
                activeSlot = nextSlot
            }
            Mode.CPU_FINISH -> {
                GLES30.glFinish()
                cpuStart = System.nanoTime()
            }
            Mode.OFF -> return
        }
        activeSection = section
    }

    fun end(section: Int) {
        if (activeSection != section) return
        when (mode) {
            Mode.GPU_TIMER_QUERY -> {
                GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
                pending[activeSlot] = true
                slotSection[activeSlot] = section
                nextSlot = (activeSlot + 1) % QUERY_SLOTS
                activeSlot = -1
            }
            Mode.CPU_FINISH -> {
                GLES30.glFinish()
                histories[section].add((System.nanoTime() - cpuStart) / NANOS_PER_MS)
            }
            Mode.OFF -> Unit
        }
        activeSection = -1
    }

    /** Collects finished GPU results into the histories. Non-blocking; call once per frame or less. */
    fun poll() {
        if (mode != Mode.GPU_TIMER_QUERY) return
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, value, 0)
        val disjoint = value[0] != 0
        for (slot in 0 until QUERY_SLOTS) {
            if (pending[slot] && resultAvailable(slot)) collect(slot, disjoint)
        }
    }

    private fun resultAvailable(slot: Int): Boolean {
        GLES30.glGetQueryObjectuiv(queries[slot], GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
        return value[0] != 0
    }

    private fun collect(slot: Int, disjoint: Boolean) {
        GLES30.glGetQueryObjectuiv(queries[slot], GLES30.GL_QUERY_RESULT, value, 0)
        pending[slot] = false
        if (disjoint) {
            skipped++
        } else {
            histories[slotSection[slot]].add((value[0].toLong() and 0xFFFFFFFFL) / NANOS_PER_MS)
        }
    }

    /** Summary of [section]'s recent costs, or null without samples. Allocates: call it for reports, not per frame. */
    fun summary(section: Int): CostSummary? = histories[section].summary()

    fun reset() {
        histories.forEach { it.clear() }
        skipped = 0
    }

    fun release() {
        if (mode == Mode.GPU_TIMER_QUERY) GLES30.glDeleteQueries(QUERY_SLOTS, queries, 0)
    }

    companion object {
        const val QUERY_SLOTS = 16
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB
        private const val NANOS_PER_MS = 1_000_000f
    }
}
