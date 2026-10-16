package com.qtekfun.ultimatecamera.core.audio.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Look-ahead sample-peak limiter with a hard ceiling (REQ-AUD-004). The channels are linked (one gain for all of them),
 * so the stereo image does not shift while limiting.
 *
 * Algorithm, per frame n, with look-ahead L frames and hold H frames:
 * 1. Required gain `r[n] = min(1, ceiling / max_c |x[n, c]|)`.
 * 2. Release: `e[n] = min(r[n], e[n-1] + (1 - e[n-1]) * k)`, a one-pole recovery towards unity with time constant
 *    [releaseSeconds] (`k` its per-sample coefficient). Gain only falls instantly; it never rises faster than the release.
 * 3. Hold: `h[n] = min(e[n-L-H] .. e[n])`, a sliding minimum over L + H + 1 frames (monotonic deque, O(1) amortised).
 *    The hold keeps the gain still between the peaks of low-frequency waves, so a steady loud tone is turned down
 *    by a constant gain instead of being modulated by it (no distortion, no pumping within a cycle).
 * 4. Attack: `g[n] = mean(h[n-L+1] .. h[n])`, a box filter of L frames. The gain ramps down linearly over L frames.
 * 5. Output `y[n] = x[n-L] * g[n]`: the signal is delayed by L frames.
 *
 * Every h[k] in the averaging window of step 4 includes e[n-L] in its own minimum (k-L-H ≤ n-L ≤ k), so
 * `g[n] ≤ e[n-L] ≤ r[n-L]` and the delayed sample can never exceed the ceiling. A final clamp to ±ceiling only absorbs
 * float rounding (≤ 1 ulp); it is not a clipper in practice.
 *
 * Latency: exactly [latencyFrames] = L frames (default 2 ms = 96 frames at 48 kHz), constant whether the limiter is
 * engaged or not, so the WAV/video sync can subtract it once. Only sample peaks are limited, not inter-sample (true)
 * peaks; the −1 dBFS default ceiling leaves headroom for the reconstruction overshoot of normal programme material.
 *
 * [ceilingDb] and [enabled] may be changed from any thread; they apply from the next buffer. Disabled, the limiter
 * keeps the same delay and only clamps at 0 dBFS.
 */
class PeakLimiter(
    override val channels: Int,
    sampleRate: Int,
    ceilingDb: Double = DEFAULT_CEILING_DB,
    lookAheadSeconds: Double = DEFAULT_LOOK_AHEAD_SECONDS,
    holdSeconds: Double = DEFAULT_HOLD_SECONDS,
    releaseSeconds: Double = DEFAULT_RELEASE_SECONDS
) : AudioProcessor {
    val latencyFrames: Int = max(1, (lookAheadSeconds * sampleRate).roundToInt())
    private val holdFrames = max(0, (holdSeconds * sampleRate).roundToInt())
    private val release = Db.onePole(releaseSeconds, sampleRate).toFloat()

    @Volatile private var ceiling = Db.toLinear(ceilingDb.coerceIn(MIN_CEILING_DB, 0.0)).toFloat()

    @Volatile var ceilingDb: Double = ceilingDb.coerceIn(MIN_CEILING_DB, 0.0)
        set(value) {
            require(!value.isNaN()) { "ceiling must be a number" }
            field = value.coerceIn(MIN_CEILING_DB, 0.0)
            ceiling = Db.toLinear(field).toFloat()
        }

    @Volatile var enabled: Boolean = true

    // Delay line of L frames, interleaved.
    private val delay = FloatArray(latencyFrames * channels)
    private var delayPos = 0

    // Sliding minimum over the hold window (monotonic deque in a ring).
    private val window = latencyFrames + holdFrames + 1
    private val dequeValue = FloatArray(window + 1)
    private val dequeIndex = LongArray(window + 1)
    private var dequeHead = 0
    private var dequeSize = 0

    // Box filter over the last L hold values.
    private val box = FloatArray(latencyFrames)
    private var boxPos = 0
    private var boxSum = 0.0

    private var envelope = 1f
    private var frameIndex = 0L

    /** Gain applied to the last frame (1 = no reduction), for metering gain reduction. */
    var currentGain: Float = 1f
        private set

    init {
        require(channels in 1..MAX_AUDIO_CHANNELS) { "channels $channels" }
        require(sampleRate > 0) { "sampleRate $sampleRate" }
        reset()
    }

    override fun process(buffer: FloatArray, frames: Int) {
        val limit = ceiling
        val active = enabled
        val clamp = if (active) limit else 1f
        val invL = 1.0 / latencyFrames
        var g = 1f
        for (f in 0 until frames) {
            val base = f * channels
            var peak = 0f
            for (c in 0 until channels) peak = max(peak, abs(buffer[base + c]))
            val required = if (active && peak > limit) limit / peak else 1f
            val recovered = envelope + (1f - envelope) * release
            envelope = if (required < recovered) required else recovered
            pushMin(envelope)
            val held = dequeValue[dequeHead]
            boxSum += held - box[boxPos]
            box[boxPos] = held
            boxPos = if (boxPos + 1 == latencyFrames) 0 else boxPos + 1
            g = (boxSum * invL).toFloat()
            if (g > 1f) g = 1f
            val d = delayPos * channels
            for (c in 0 until channels) {
                val delayed = delay[d + c]
                delay[d + c] = buffer[base + c]
                val y = delayed * g
                buffer[base + c] = if (y > clamp) {
                    clamp
                } else if (y < -clamp) {
                    -clamp
                } else {
                    y
                }
            }
            delayPos = if (delayPos + 1 == latencyFrames) 0 else delayPos + 1
            frameIndex++
        }
        if (frames > 0) currentGain = g
        // Recompute the running sum now and then so float error cannot accumulate over hours.
        if (frameIndex and RESUM_MASK < frames) resum()
    }

    private fun pushMin(value: Float) {
        val cap = dequeValue.size
        while (dequeSize > 0) {
            val last = (dequeHead + dequeSize - 1) % cap
            if (dequeValue[last] >= value) dequeSize-- else break
        }
        val slot = (dequeHead + dequeSize) % cap
        dequeValue[slot] = value
        dequeIndex[slot] = frameIndex
        dequeSize++
        while (dequeIndex[dequeHead] <= frameIndex - window) {
            dequeHead = (dequeHead + 1) % cap
            dequeSize--
        }
    }

    private fun resum() {
        var s = 0.0
        for (v in box) s += v
        boxSum = s
    }

    override fun reset() {
        delay.fill(0f)
        delayPos = 0
        box.fill(1f)
        boxPos = 0
        boxSum = latencyFrames.toDouble()
        dequeHead = 0
        dequeSize = 0
        envelope = 1f
        currentGain = 1f
        frameIndex = 0
    }

    companion object {
        const val DEFAULT_CEILING_DB = -1.0
        const val DEFAULT_LOOK_AHEAD_SECONDS = 0.002
        const val DEFAULT_HOLD_SECONDS = 0.020
        const val DEFAULT_RELEASE_SECONDS = 0.150
        const val MIN_CEILING_DB = -24.0
        private const val RESUM_MASK = (1L shl 16) - 1
    }
}
