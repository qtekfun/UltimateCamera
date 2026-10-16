package com.qtekfun.ultimatecamera.core.audio.dsp

/**
 * Channel selection and mapping (REQ-AUD-004): which input channels of the device end up in each recorded channel.
 * Each output channel is a weighted sum of input channels (`weights[out * inputChannels + in]`), which covers mono from
 * L, R or a mix, stereo, swapping, and picking any channels of a multichannel USB interface.
 *
 * [apply] works in place: the buffer holds `frames * inputChannels` samples on entry and `frames * outputChannels` on
 * exit, so it must be at least `frames * max(inputChannels, outputChannels)` long. No allocation per call.
 */
class ChannelMap private constructor(val inputChannels: Int, val outputChannels: Int, private val weights: FloatArray) {
    private val frame = FloatArray(inputChannels)

    /** Bit `i` set when input channel `i` contributes to output channel [output]. Used to route clip flags. */
    fun sourceMask(output: Int): Long {
        var mask = 0L
        for (i in 0 until inputChannels) if (weights[output * inputChannels + i] != 0f) mask = mask or (1L shl i)
        return mask
    }

    val isIdentity: Boolean = inputChannels == outputChannels &&
        (0 until outputChannels).all { o -> (0 until inputChannels).all { i -> weights[o * inputChannels + i] == if (i == o) 1f else 0f } }

    fun weight(output: Int, input: Int): Float = weights[output * inputChannels + input]

    fun apply(buffer: FloatArray, frames: Int) {
        if (isIdentity || frames == 0) return
        if (outputChannels <= inputChannels) {
            // Writes never pass the frame being read: forwards is safe once the frame is copied out.
            for (f in 0 until frames) mapFrame(buffer, f)
        } else {
            for (f in frames - 1 downTo 0) mapFrame(buffer, f)
        }
    }

    private fun mapFrame(buffer: FloatArray, f: Int) {
        System.arraycopy(buffer, f * inputChannels, frame, 0, inputChannels)
        val out = f * outputChannels
        for (o in 0 until outputChannels) {
            var sum = 0f
            val row = o * inputChannels
            for (i in 0 until inputChannels) sum += weights[row + i] * frame[i]
            buffer[out + o] = sum
        }
    }

    override fun toString(): String = "ChannelMap($inputChannels→$outputChannels)"

    /** How a mono recording is taken from a stereo or multichannel input. */
    enum class MonoSource { LEFT, RIGHT, MIX }

    companion object {
        /** Every input channel unchanged. */
        fun identity(channels: Int): ChannelMap = matrix(channels, channels, FloatArray(channels * channels) { if (it / channels == it % channels) 1f else 0f })

        /** Mono from the left, right or the average of both (−6 dB each, so a mix cannot clip). */
        fun mono(inputChannels: Int, source: MonoSource): ChannelMap {
            require(inputChannels >= 1) { "inputChannels $inputChannels" }
            if (inputChannels == 1) return identity(1)
            return when (source) {
                MonoSource.LEFT -> pick(inputChannels, 0)
                MonoSource.RIGHT -> pick(inputChannels, 1)
                MonoSource.MIX -> matrix(inputChannels, 1, FloatArray(inputChannels) { if (it < 2) 0.5f else 0f })
            }
        }

        /** Stereo from a mono input (the same signal on both sides) or from the first two channels of a wider one. */
        fun stereo(inputChannels: Int): ChannelMap = if (inputChannels == 1) pick(1, 0, 0) else pick(inputChannels, 0, 1)

        /** Output channel `k` is input channel `sources[k]` (0-based); a channel may be repeated. */
        fun pick(inputChannels: Int, vararg sources: Int): ChannelMap {
            require(sources.isNotEmpty()) { "no output channels" }
            val w = FloatArray(sources.size * inputChannels)
            sources.forEachIndexed { o, i ->
                require(i in 0 until inputChannels) { "input channel $i out of 0 until $inputChannels" }
                w[o * inputChannels + i] = 1f
            }
            return matrix(inputChannels, sources.size, w)
        }

        /** Free mapping; [weights] is row-major, one row of [inputChannels] weights per output channel. */
        fun matrix(inputChannels: Int, outputChannels: Int, weights: FloatArray): ChannelMap {
            require(inputChannels in 1..MAX_AUDIO_CHANNELS && outputChannels in 1..MAX_AUDIO_CHANNELS) {
                "channels $inputChannels→$outputChannels"
            }
            require(weights.size == inputChannels * outputChannels) { "weights size ${weights.size}" }
            require(weights.all { it.isFinite() }) { "weights must be finite" }
            return ChannelMap(inputChannels, outputChannels, weights.copyOf())
        }
    }
}
