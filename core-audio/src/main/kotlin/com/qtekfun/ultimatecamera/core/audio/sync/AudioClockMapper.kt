package com.qtekfun.ultimatecamera.core.audio.sync

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Maps AudioRecord frame positions to the video clock and back (REQ-AUD-003: separate WAV track kept in sync).
 *
 * Feed it the pairs from `AudioRecord.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)` (`ts.framePosition`,
 * `ts.nanoTime`, same base as `System.nanoTime()`), or `TIMEBASE_BOOTTIME` when the camera reports
 * `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (sensor timestamps on `elapsedRealtimeNanos`); the mapper does not care which,
 * as long as the video timestamps use the same base. It fits `nanos = intercept + frames * nanosPerFrame` by least
 * squares over the last [capacity] pairs, which averages the timestamp jitter out and measures the real sample rate of
 * the device's clock ([measuredSampleRate], [driftPpm]) against [nominalSampleRate].
 *
 * Robustness: a pair whose residual exceeds [outlierNanos] against an established fit is rejected (counted in
 * [rejected]); [rejectionsBeforeReset] consecutive rejections, or a frame position going backwards, mean the stream was
 * restarted or glitched and the fit starts again from that pair.
 *
 * Until two pairs are known the mapping uses the nominal rate from the first pair. No allocation after construction;
 * not thread-safe (call from the audio thread, or confine to one thread).
 */
class AudioClockMapper(
    val nominalSampleRate: Int,
    val capacity: Int = DEFAULT_CAPACITY,
    private val outlierNanos: Long = DEFAULT_OUTLIER_NANOS,
    private val rejectionsBeforeReset: Int = DEFAULT_REJECTIONS_BEFORE_RESET
) {
    private val frames = LongArray(capacity)
    private val nanos = LongArray(capacity)
    private var head = 0
    private var size = 0
    private var originFrame = 0L
    private var originNanos = 0L
    private var consecutiveRejections = 0

    // Fit relative to the origin: nanos - originNanos = a + b * (frames - originFrame).
    private var a = 0.0
    private var b = NANOS_PER_SECOND / nominalSampleRate

    /** Pairs rejected as outliers since construction or [reset]. */
    var rejected: Int = 0
        private set

    /** Pairs currently in the fit. */
    val points: Int get() = size

    init {
        require(nominalSampleRate > 0) { "nominalSampleRate $nominalSampleRate" }
        require(capacity >= 2) { "capacity $capacity" }
    }

    /** Adds one timestamp pair. Returns false if it was rejected as an outlier. */
    fun add(framePosition: Long, nanoTime: Long): Boolean {
        if (size > 0) {
            val last = frames[(head + size - 1) % capacity]
            if (framePosition < last) restart()
        }
        if (size == 0) {
            originFrame = framePosition
            originNanos = nanoTime
        } else if (size >= MIN_POINTS_FOR_REJECTION) {
            val residual = abs(nanoTime - frameToNanos(framePosition))
            if (residual > outlierNanos) {
                rejected++
                consecutiveRejections++
                if (consecutiveRejections < rejectionsBeforeReset) return false
                restart()
                originFrame = framePosition
                originNanos = nanoTime
            }
        }
        consecutiveRejections = 0
        if (size == capacity) {
            head = (head + 1) % capacity
            size--
        }
        val slot = (head + size) % capacity
        frames[slot] = framePosition
        nanos[slot] = nanoTime
        size++
        fit()
        return true
    }

    private fun restart() {
        head = 0
        size = 0
        consecutiveRejections = 0
        a = 0.0
        b = NANOS_PER_SECOND / nominalSampleRate
    }

    private fun fit() {
        if (size < 2) {
            a = (nanos[head] - originNanos) - b * (frames[head] - originFrame)
            return
        }
        var sx = 0.0
        var sy = 0.0
        for (k in 0 until size) {
            val i = (head + k) % capacity
            sx += (frames[i] - originFrame).toDouble()
            sy += (nanos[i] - originNanos).toDouble()
        }
        val mx = sx / size
        val my = sy / size
        var sxx = 0.0
        var sxy = 0.0
        for (k in 0 until size) {
            val i = (head + k) % capacity
            val dx = (frames[i] - originFrame) - mx
            sxx += dx * dx
            sxy += dx * ((nanos[i] - originNanos) - my)
        }
        if (sxx > 0.0) b = sxy / sxx
        a = my - b * mx
    }

    /** Video-clock time (ns) at which [framePosition] was captured. */
    fun frameToNanos(framePosition: Long): Long = originNanos + (a + b * (framePosition - originFrame)).roundToLong()

    /** Fractional frame position captured at [nanoTime]. */
    fun nanosToFrame(nanoTime: Long): Double = originFrame + ((nanoTime - originNanos) - a) / b

    /**
     * Frames of the WAV to skip (positive) or of silence to prepend (negative) so that WAV frame 0 lines up with the
     * first video frame at [videoStartNanos]. [firstFramePosition] is the AudioRecord position of the first frame written
     * to the WAV and [processingLatencyFrames] the chain delay (`AudioChain.latencyFrames`): output frame `k` of the chain
     * holds input frame `k - latency`, so the WAV is shifted by it.
     */
    fun wavStartOffsetFrames(videoStartNanos: Long, firstFramePosition: Long, processingLatencyFrames: Int = 0): Long =
        floor(nanosToFrame(videoStartNanos) + 0.5).toLong() - firstFramePosition + processingLatencyFrames

    /** Sample rate of the device's audio clock measured against the video clock (Hz). */
    val measuredSampleRate: Double get() = NANOS_PER_SECOND / b

    /** Audio clock error in parts per million: positive when the device produces more samples per second than nominal. */
    val driftPpm: Double get() = (measuredSampleRate / nominalSampleRate - 1.0) * PPM

    /**
     * Frames by which a WAV that assumes [nominalSampleRate] is ahead (positive) or behind of the video after
     * [elapsedNanos]: what an editor would have to stretch, or what a resampler has to absorb.
     */
    fun driftFrames(elapsedNanos: Long): Double = elapsedNanos / NANOS_PER_SECOND * (measuredSampleRate - nominalSampleRate)

    fun reset() {
        restart()
        rejected = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_OUTLIER_NANOS = 2_000_000L
        const val DEFAULT_REJECTIONS_BEFORE_RESET = 4
        private const val MIN_POINTS_FOR_REJECTION = 8
        private const val NANOS_PER_SECOND = 1e9
        private const val PPM = 1e6
    }
}
