package com.qtekfun.ultimatecamera.core.recording

/**
 * Wall time per stage of a long job (the portrait render, D73), for the log and the on-device test: each [mark] closes
 * the stage that ran since the previous one. [now] is injectable for tests.
 */
class StageTimer(private val now: () -> Long = System::nanoTime) {
    private var last = now()
    private val stages = ArrayList<Pair<String, Long>>()

    fun mark(stage: String) {
        val t = now()
        stages += stage to (t - last) / NANOS_PER_MS
        last = t
    }

    /** Milliseconds of each stage, in order. */
    val millis: List<Pair<String, Long>> get() = stages.toList()

    val totalMillis: Long get() = stages.sumOf { it.second }

    override fun toString(): String = stages.joinToString(", ", postfix = ", total $totalMillis ms") { (name, ms) -> "$name $ms ms" }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
