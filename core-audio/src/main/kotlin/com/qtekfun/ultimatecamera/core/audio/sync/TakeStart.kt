package com.qtekfun.ultimatecamera.core.audio.sync

/**
 * Sample-accurate start of a separate audio file (REQ-AUD-003): the WAV must begin at the take's first video frame,
 * not at the first whole capture buffer after it, so that it lines up with the MP4 in an editor without nudging.
 */
object TakeStart {
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /**
     * Frames to drop from the start of a buffer of [frames] frames whose first frame is at [bufferNanos], so that the
     * kept audio starts at [startNanos]; null when the whole buffer is before the start. 0 when the buffer starts at or
     * after it (the take began earlier, or the clock fit stepped).
     */
    fun framesToSkip(bufferNanos: Long, frames: Int, startNanos: Long, sampleRate: Int): Int? {
        if (bufferNanos >= startNanos) return 0
        val skip = Math.round((startNanos - bufferNanos) * sampleRate / NANOS_PER_SECOND)
        return if (skip >= frames) null else skip.toInt()
    }
}
