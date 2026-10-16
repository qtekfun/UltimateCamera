package com.qtekfun.ultimatecamera.core.audio.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * One stage of the real-time audio chain (REQ-AUD-004). It works in place on interleaved float PCM (full scale ±1.0)
 * with the channel count fixed when the stage is built.
 *
 * Contract for every implementation: [process] runs on the audio thread for every buffer and must not allocate, lock or
 * block; all state is preallocated in the constructor. Parameters set from other threads are `@Volatile` fields that
 * [process] reads once per buffer.
 */
interface AudioProcessor {
    val channels: Int

    /** Processes the first [frames] frames of [buffer] (`frames * channels` samples) in place. */
    fun process(buffer: FloatArray, frames: Int)

    /** Back to the initial state, as if no audio had been processed (e.g. before a new take). */
    fun reset()
}

/** dB ↔ linear helpers shared by the DSP stages. Amplitude dB (20·log10). */
object Db {
    fun toLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Linear amplitude to dB; 0 or less is −∞. */
    fun fromLinear(linear: Double): Double = if (linear <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(linear)

    /** Per-sample coefficient of a one-pole smoother with time constant [seconds] (63 % of a step after that time). */
    fun onePole(seconds: Double, sampleRate: Int): Double = if (seconds <= 0.0) 1.0 else 1.0 - exp(-1.0 / (seconds * sampleRate))
}

/** Highest channel count the chain accepts (USB audio class interfaces; Android's AudioRecord limit is lower). */
const val MAX_AUDIO_CHANNELS = 32
