package com.qtekfun.ultimatecamera.core.gpu.gl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Gaps between consecutive encoded frames, from their camera timestamps. With [expectedIntervalNanos] (1 s / fps) set,
 * a step of more than 1.5 intervals is a gap: it adds the frames missing in it to [drops] and one to [gaps], so a camera
 * running a fraction of a percent off its nominal rate is not mistaken for drops. The largest step since [startWindow]
 * is kept too, so a probe can say how long the one gap was (D83). [onFrame] and [reset] run on the GL thread; the
 * counters can be read and [startWindow] called from any thread.
 */
class FrameGapCounter {
    /** 0 disables gap counting (the largest step is still kept). */
    @Volatile var expectedIntervalNanos = 0L

    /** Frames missing in the gaps since [reset]. */
    val drops = AtomicInteger()

    /** Gaps since [reset], however many frames each one lost. */
    val gaps = AtomicInteger()

    private val maxStep = AtomicLong(0)
    private var last = -1L

    /** Largest step between two consecutive frames since [startWindow] (or [reset]), in ns; 0 before two frames. */
    val maxStepNanos: Long get() = maxStep.get()

    fun reset() {
        drops.set(0)
        gaps.set(0)
        maxStep.set(0)
        last = -1L
    }

    /** Starts a new window for [maxStepNanos]; [drops] and [gaps] keep counting (read them as differences). */
    fun startWindow() = maxStep.set(0)

    fun onFrame(timestampNanos: Long) {
        val previous = last
        last = timestampNanos
        if (previous < 0) return
        val step = timestampNanos - previous
        if (step > maxStep.get()) maxStep.set(step)
        val interval = expectedIntervalNanos
        if (interval <= 0 || step * 2 <= interval * 3) return
        drops.addAndGet(((step + interval / 2) / interval - 1).toInt())
        gaps.incrementAndGet()
    }
}
