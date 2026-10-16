package com.qtekfun.ultimatecamera.core.audio.dsp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Phase 3 real-time chain between `AudioRecord.read` and the encoders / WAV writer (REQ-AUD-004):
 *
 * `input (N ch) → clip detection → channel map (N → M) → gain → limiter → meter → output (M ch)`
 *
 * The meter sits at the end, so it shows what is recorded; its clip flag comes from the input (an ADC overload cannot
 * be undone by the limiter). Buffers are interleaved and processed in place: 16-bit PCM is converted into a
 * preallocated float work buffer and back (rounded, clamped); float PCM is processed directly. Nothing is allocated
 * per buffer; [maxFrames] is the largest buffer accepted, normally the frames per `AudioRecord.read`.
 *
 * The output is delayed by [latencyFrames] (the limiter look-ahead): output frame `k` is input frame `k - latencyFrames`.
 * The sync mapping must subtract it (see `AudioClockMapper`). Thread rules as in [AudioProcessor]: one audio thread
 * calls [process]; [gain], [limiter] parameters and [meter] reads are safe from any thread.
 */
class AudioChain(
    val sampleRate: Int,
    val channelMap: ChannelMap,
    val maxFrames: Int,
    gainDb: Double = 0.0,
    ceilingDb: Double = PeakLimiter.DEFAULT_CEILING_DB
) {
    val inputChannels: Int = channelMap.inputChannels
    val outputChannels: Int = channelMap.outputChannels

    val gain = GainProcessor(outputChannels, sampleRate, gainDb)
    val limiter = PeakLimiter(outputChannels, sampleRate, ceilingDb)
    val meter = AudioMeter(outputChannels, sampleRate)

    val latencyFrames: Int get() = limiter.latencyFrames

    private val work = FloatArray(maxFrames * max(inputChannels, outputChannels))
    private val sourceMasks = LongArray(outputChannels) { channelMap.sourceMask(it) }

    init {
        require(sampleRate > 0) { "sampleRate $sampleRate" }
        require(maxFrames > 0) { "maxFrames $maxFrames" }
    }

    /**
     * Processes [frames] interleaved frames of [inputChannels] in [pcm] and writes `frames * outputChannels` samples back
     * at its start. [pcm] must hold `frames * max(inputChannels, outputChannels)` samples. Returns the samples written.
     */
    fun process(pcm: ShortArray, frames: Int): Int {
        checkFrames(frames, pcm.size)
        val inSamples = frames * inputChannels
        var clipped = 0L
        var c = 0
        for (i in 0 until inSamples) {
            val s = pcm[i].toInt()
            if (s >= Short.MAX_VALUE || s <= Short.MIN_VALUE) clipped = clipped or (1L shl c)
            work[i] = s * FROM_SHORT
            c = if (c + 1 == inputChannels) 0 else c + 1
        }
        run(work, frames, clipped)
        val outSamples = frames * outputChannels
        for (i in 0 until outSamples) {
            val v = (work[i] * TO_SHORT).roundToInt()
            pcm[i] = (
                if (v > Short.MAX_VALUE) {
                    Short.MAX_VALUE.toInt()
                } else if (v < Short.MIN_VALUE) {
                    Short.MIN_VALUE.toInt()
                } else {
                    v
                }
                ).toShort()
        }
        return outSamples
    }

    /** As the 16-bit [process], for `ENCODING_PCM_FLOAT` buffers (full scale ±1.0); processed directly in [pcm]. */
    fun process(pcm: FloatArray, frames: Int): Int {
        checkFrames(frames, pcm.size)
        val inSamples = frames * inputChannels
        var clipped = 0L
        var c = 0
        for (i in 0 until inSamples) {
            val x = pcm[i]
            if (x >= 1f || x <= -1f) clipped = clipped or (1L shl c)
            c = if (c + 1 == inputChannels) 0 else c + 1
        }
        run(pcm, frames, clipped)
        return frames * outputChannels
    }

    private fun run(buffer: FloatArray, frames: Int, inputClipMask: Long) {
        channelMap.apply(buffer, frames)
        gain.process(buffer, frames)
        limiter.process(buffer, frames)
        if (inputClipMask != 0L) {
            var outMask = 0L
            for (o in 0 until outputChannels) if (sourceMasks[o] and inputClipMask != 0L) outMask = outMask or (1L shl o)
            meter.markClipped(outMask)
        }
        meter.process(buffer, frames)
    }

    private fun checkFrames(frames: Int, size: Int) {
        require(frames in 0..maxFrames) { "frames $frames outside 0..$maxFrames" }
        require(size >= frames * max(inputChannels, outputChannels)) { "buffer too small: $size" }
    }

    /** Clears every stage for a new take (gain and limiter settings are kept). */
    fun reset() {
        gain.reset()
        limiter.reset()
        meter.reset()
    }

    private companion object {
        const val FROM_SHORT = 1f / 32768f
        const val TO_SHORT = 32768f
    }
}
