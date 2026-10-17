package com.qtekfun.ultimatecamera.core.gpu

/**
 * Decides, per camera frame, whether the proxy encoder gets it (D59). Two rules, both allocation-free:
 *
 * - **Rate:** frames are thinned evenly from [sourceFps] to [targetFps] (e.g. 60 → 30 keeps every other frame; 50 → 30
 *   keeps 3 of every 5), Bresenham-style, so the proxy has a steady cadence.
 * - **Backlog:** when the proxy encoder has more than [maxBacklog] frames submitted but not yet encoded, the frame is
 *   skipped instead of queued. The proxy falling behind then costs proxy frames only: the render thread never waits on
 *   the proxy's input queue, so the main encoder is never delayed.
 *
 * Used from the render thread only; not thread-safe.
 */
class ProxyFrameGate(private val sourceFps: Int, private val targetFps: Int, private val maxBacklog: Int = DEFAULT_MAX_BACKLOG) {
    init {
        require(sourceFps > 0 && targetFps > 0 && maxBacklog > 0) { "invalid gate $sourceFps → $targetFps, backlog $maxBacklog" }
    }

    private var credit = 0

    /** Frames handed to the proxy encoder. */
    var submitted = 0
        private set

    /** Frames the rate allowed but the backlog refused (the proxy fell behind). */
    var skippedBehind = 0
        private set

    /** True when this frame goes to the proxy; [encodedSoFar] is the proxy encoder's output count. */
    fun shouldDraw(encodedSoFar: Int): Boolean {
        credit += minOf(targetFps, sourceFps)
        if (credit < sourceFps) return false
        credit -= sourceFps
        if (submitted - encodedSoFar >= maxBacklog) {
            skippedBehind++
            return false
        }
        submitted++
        return true
    }

    companion object {
        /** A hardware encoder holds a few frames in flight; more than this means it cannot keep up. */
        const val DEFAULT_MAX_BACKLOG = 6
    }
}
