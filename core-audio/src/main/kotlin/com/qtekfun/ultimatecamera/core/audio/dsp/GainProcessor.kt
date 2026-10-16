package com.qtekfun.ultimatecamera.core.audio.dsp

import kotlin.math.abs

/**
 * Manual gain in dB (REQ-AUD-004). A change of [gainDb] does not jump: the applied linear gain follows the target with a
 * per-sample one-pole smoother (time constant [smoothingSeconds], default 20 ms), so there is no zipper noise. Once the
 * applied gain is within 1e-7 of the target it snaps to it and the buffer is a plain multiply.
 *
 * The gain is the same for every channel. [gainDb] may be set from any thread; it is clamped to [MIN_DB]..[MAX_DB].
 */
class GainProcessor(override val channels: Int, sampleRate: Int, gainDb: Double = 0.0, smoothingSeconds: Double = DEFAULT_SMOOTHING_SECONDS) : AudioProcessor {
    private val coefficient = Db.onePole(smoothingSeconds, sampleRate)

    @Volatile private var target = Db.toLinear(gainDb.coerceIn(MIN_DB, MAX_DB))

    @Volatile var gainDb: Double = gainDb.coerceIn(MIN_DB, MAX_DB)
        set(value) {
            require(!value.isNaN()) { "gain must be a number" }
            val clamped = value.coerceIn(MIN_DB, MAX_DB)
            field = clamped
            target = Db.toLinear(clamped)
        }

    // Smoother state in double: in float it would stall ~1e-4 short of the target (the step rounds away).
    private var current = target

    /** Linear gain applied to the last sample processed (for tests and diagnostics). */
    val appliedLinear: Double get() = current

    init {
        require(channels in 1..MAX_AUDIO_CHANNELS) { "channels $channels" }
        require(sampleRate > 0) { "sampleRate $sampleRate" }
    }

    override fun process(buffer: FloatArray, frames: Int) {
        val goal = target
        var g = current
        var i = 0
        val end = frames * channels
        if (g == goal) {
            val k = g.toFloat()
            if (k != 1f) {
                while (i < end) {
                    buffer[i] *= k
                    i++
                }
            }
            return
        }
        while (i < end) {
            g += (goal - g) * coefficient
            if (abs(goal - g) < SNAP) g = goal
            val k = g.toFloat()
            for (c in 0 until channels) buffer[i + c] *= k
            i += channels
        }
        current = g
    }

    override fun reset() {
        current = target
    }

    companion object {
        const val MIN_DB = -60.0
        const val MAX_DB = 48.0
        const val DEFAULT_SMOOTHING_SECONDS = 0.020
        private const val SNAP = 1e-7
    }
}
