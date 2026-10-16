package com.qtekfun.ultimatecamera.core.audio.dsp

import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Level meter for the VU display (REQ-AUD-004 "vúmetro"): per channel, in dBFS (full scale ±1.0 = 0 dBFS).
 *
 * Ballistics (digital peak programme meter, IEC 60268-18 style):
 * - **Peak**: sample peak, instant attack, falls back at [decayDbPerSecond] (default 20 dB per 1.7 s ≈ 11.8 dB/s).
 * - **Peak hold**: the highest peak stays for [holdSeconds] (default 1.5 s), then falls at the same rate.
 * - **RMS**: exponential average of the squared signal with time constant [rmsSeconds] (default 300 ms, VU-like
 *   integration). Not AES17-shifted: a full-scale sine reads 0 dBFS peak and −3.01 dBFS RMS.
 * - **Clip**: set when the *input* reached full scale (see [markClipped]) and kept for [clipHoldSeconds] (default 2 s),
 *   plus a running count of clipped buffers per channel for the take.
 *
 * Threads: [process] (audio thread) publishes a snapshot after every buffer into an atomic array guarded by a sequence
 * number (seqlock); [read] (UI thread, e.g. at 30 Hz) copies it into a caller-owned [MeterSnapshot] without locks or
 * allocation and retries if the audio thread published meanwhile. Only one thread may call [process].
 */
class AudioMeter(
    override val channels: Int,
    private val sampleRate: Int,
    decayDbPerSecond: Double = DEFAULT_DECAY_DB_PER_SECOND,
    holdSeconds: Double = DEFAULT_HOLD_SECONDS,
    rmsSeconds: Double = DEFAULT_RMS_SECONDS,
    clipHoldSeconds: Double = DEFAULT_CLIP_HOLD_SECONDS
) : AudioProcessor {
    private val decay = Db.toLinear(-decayDbPerSecond / sampleRate).toFloat()
    private val rmsCoefficient = Db.onePole(rmsSeconds, sampleRate).toFloat()
    private val holdFrames = (holdSeconds * sampleRate).roundToInt().toLong()
    private val clipHoldFrames = (clipHoldSeconds * sampleRate).roundToInt().toLong()

    private val peak = FloatArray(channels)
    private val meanSquare = FloatArray(channels)
    private val hold = FloatArray(channels)
    private val holdLeft = LongArray(channels)
    private val clipLeft = LongArray(channels)
    private val clipCount = IntArray(channels)
    private var pendingClip = 0L

    // Published values: [sequence, then per channel: peak, hold, rms, clip, clipCount] as float/int bits.
    private val published = AtomicIntegerArray(1 + channels * FIELDS)

    init {
        require(channels in 1..MAX_AUDIO_CHANNELS) { "channels $channels" }
        require(sampleRate > 0) { "sampleRate $sampleRate" }
    }

    /** Flags channels whose input reached full scale in the buffer about to be [process]ed (bit `c` = channel `c`). */
    fun markClipped(channelMask: Long) {
        pendingClip = pendingClip or channelMask
    }

    override fun process(buffer: FloatArray, frames: Int) {
        for (c in 0 until channels) {
            var p = peak[c]
            var ms = meanSquare[c]
            var h = hold[c]
            var left = holdLeft[c]
            var i = c
            repeat(frames) {
                val x = buffer[i]
                val a = abs(x)
                p *= decay
                if (a > p) p = a
                ms += (x * x - ms) * rmsCoefficient
                if (a >= h) {
                    h = a
                    left = holdFrames
                } else if (left > 0) {
                    left--
                } else {
                    h = max(h * decay, p)
                }
                i += channels
            }
            if (ms < DENORMAL_FLOOR) ms = 0f
            if (p < DENORMAL_FLOOR) p = 0f
            peak[c] = p
            meanSquare[c] = ms
            hold[c] = h
            holdLeft[c] = left
            if (pendingClip and (1L shl c) != 0L) {
                clipLeft[c] = clipHoldFrames
                clipCount[c]++
            } else {
                clipLeft[c] = max(0L, clipLeft[c] - frames)
            }
        }
        pendingClip = 0L
        publish()
    }

    private fun publish() {
        val seq = published.get(0) + 1
        published.set(0, seq) // Odd: write in progress.
        for (c in 0 until channels) {
            val o = 1 + c * FIELDS
            published.set(o, peak[c].toRawBits())
            published.set(o + 1, hold[c].toRawBits())
            published.set(o + 2, sqrt(meanSquare[c]).toRawBits())
            published.set(o + 3, if (clipLeft[c] > 0) 1 else 0)
            published.set(o + 4, clipCount[c])
        }
        published.set(0, seq + 1)
    }

    /**
     * Copies the latest published levels into [into] (sized for at least [channels]). Lock-free and allocation-free.
     * Returns false only if the audio thread kept publishing during [MAX_READ_ATTEMPTS] attempts (then [into] is stale).
     */
    fun read(into: MeterSnapshot): Boolean {
        require(into.capacity >= channels) { "snapshot holds ${into.capacity} channels, meter has $channels" }
        repeat(MAX_READ_ATTEMPTS) {
            val before = published.get(0)
            if (before and 1 == 0) {
                for (c in 0 until channels) {
                    val o = 1 + c * FIELDS
                    into.peakLinear[c] = Float.fromBits(published.get(o))
                    into.holdLinear[c] = Float.fromBits(published.get(o + 1))
                    into.rmsLinear[c] = Float.fromBits(published.get(o + 2))
                    into.clip[c] = published.get(o + 3) != 0
                    into.clipCount[c] = published.get(o + 4)
                }
                if (published.get(0) == before) {
                    into.channels = channels
                    into.sequence = before
                    return true
                }
            }
        }
        return false
    }

    override fun reset() {
        peak.fill(0f)
        meanSquare.fill(0f)
        hold.fill(0f)
        holdLeft.fill(0L)
        clipLeft.fill(0L)
        clipCount.fill(0)
        pendingClip = 0L
        publish()
    }

    companion object {
        /** IEC 60268-18 return time: 20 dB in 1.7 s. */
        const val DEFAULT_DECAY_DB_PER_SECOND = 20.0 / 1.7
        const val DEFAULT_HOLD_SECONDS = 1.5
        const val DEFAULT_RMS_SECONDS = 0.300
        const val DEFAULT_CLIP_HOLD_SECONDS = 2.0
        private const val FIELDS = 5
        private const val MAX_READ_ATTEMPTS = 64
        private const val DENORMAL_FLOOR = 1e-20f
    }
}

/**
 * Caller-owned copy of the meter values; allocate once (e.g. per meter composable) and pass it to [AudioMeter.read].
 * Linear values (1.0 = full scale); the `*Dbfs` accessors convert without allocating.
 */
class MeterSnapshot(val capacity: Int = MAX_AUDIO_CHANNELS) {
    val peakLinear = FloatArray(capacity)
    val holdLinear = FloatArray(capacity)
    val rmsLinear = FloatArray(capacity)
    val clip = BooleanArray(capacity)
    val clipCount = IntArray(capacity)

    /** Channels filled by the last successful read; 0 before the first one. */
    var channels: Int = 0
        internal set

    /** Changes whenever the meter published new values; the UI can skip redrawing when it did not. */
    var sequence: Int = 0
        internal set

    fun peakDbfs(channel: Int): Double = Db.fromLinear(peakLinear[channel].toDouble())
    fun holdDbfs(channel: Int): Double = Db.fromLinear(holdLinear[channel].toDouble())
    fun rmsDbfs(channel: Int): Double = Db.fromLinear(rmsLinear[channel].toDouble())
}
