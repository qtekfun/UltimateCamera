package com.qtekfun.ultimatecamera.core.storage

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything a recording writes at once, in bits per second. Feeds the speed verdict and the time-remaining estimate.
 *
 * @property videoBitsPerSecond the encoder's target bitrate.
 * @property audioBitsPerSecond the AAC track inside the container.
 * @property separateWavBitsPerSecond the separate WAV/PCM file (REQ-AUD-003), 0 if off; see [wavBitsPerSecond].
 * @property proxyBitsPerSecond a proxy file written at the same time (later), 0 if off.
 */
data class RecordingStreams(
    val videoBitsPerSecond: Long,
    val audioBitsPerSecond: Long = AAC_BITS_PER_SECOND,
    val separateWavBitsPerSecond: Long = 0,
    val proxyBitsPerSecond: Long = 0
) {
    init {
        require(videoBitsPerSecond > 0) { "video bitrate must be positive" }
        require(audioBitsPerSecond >= 0 && separateWavBitsPerSecond >= 0 && proxyBitsPerSecond >= 0) { "bitrates must not be negative" }
    }

    val totalBitsPerSecond: Long get() = videoBitsPerSecond + audioBitsPerSecond + separateWavBitsPerSecond + proxyBitsPerSecond

    companion object {
        /** Stereo AAC-LC at 48 kHz, a common default. */
        const val AAC_BITS_PER_SECOND = 256_000L

        /** The AAC bitrate the recorder uses (core-recording's `AudioConfig`). */
        const val RECORDER_AAC_BITS_PER_SECOND = 192_000L

        /** The separate WAV the recorder writes (REQ-AUD-003): 48 kHz, 16-bit, stereo = 1.536 Mbit/s. */
        val SEPARATE_WAV_BITS_PER_SECOND: Long = wavBitsPerSecond(sampleRate = 48_000, bitsPerSample = 16, channels = 2)

        /**
         * 4K30 10-bit HEVC at the default 0.2 bit/pixel (D40, ≈ 50 Mbit/s) with the recorder's AAC: what the write-speed
         * verdict assumes before the camera has opened a mode (see [ActiveRecordingStreams]).
         */
        fun uhd30TenBit(separateWav: Boolean): RecordingStreams = RecordingStreams(
            videoBitsPerSecond = UHD_30_10BIT_VIDEO_BITS_PER_SECOND,
            audioBitsPerSecond = RECORDER_AAC_BITS_PER_SECOND,
            separateWavBitsPerSecond = if (separateWav) SEPARATE_WAV_BITS_PER_SECOND else 0
        )

        private const val UHD_30_10BIT_VIDEO_BITS_PER_SECOND = 3840L * 2160 * 30 / 5 // 0.2 bit/pixel.

        /** Uncompressed PCM: e.g. 48 kHz, 24-bit, stereo = 2.304 Mbit/s. */
        fun wavBitsPerSecond(sampleRate: Int, bitsPerSample: Int, channels: Int): Long = sampleRate.toLong() * bitsPerSample * channels

        /**
         * The heaviest v1 profile as measured on the OPPO (4K60 10-bit HEVC at ~125 Mbit/s) with AAC and a separate
         * 48 kHz 24-bit stereo WAV: ~127.6 Mbit/s in total.
         */
        val UHD_60_10BIT = RecordingStreams(
            videoBitsPerSecond = 125_000_000L,
            separateWavBitsPerSecond = wavBitsPerSecond(sampleRate = 48_000, bitsPerSample = 24, channels = 2)
        )
    }
}

/**
 * The streams of the mode the camera has open (published by the camera screen, read by Settings for the write-speed
 * verdict); null until the camera opened once in this process.
 */
object ActiveRecordingStreams {
    val current: MutableStateFlow<RecordingStreams?> = MutableStateFlow(null)
}

/** How a measured write speed compares with what a profile needs (REQ-STO-001 warning). */
enum class SpeedVerdict(@StringRes val messageRes: Int) {
    /** At least [WriteSpeedPolicy.OK_MARGIN] times the profile's bitrate. */
    OK(R.string.storage_speed_ok),

    /** Between [WriteSpeedPolicy.MIN_MARGIN] and [WriteSpeedPolicy.OK_MARGIN] times: may drop frames when the drive slows. */
    MARGINAL(R.string.storage_speed_marginal),

    /** Below [WriteSpeedPolicy.MIN_MARGIN] times: will drop frames. */
    INSUFFICIENT(R.string.storage_speed_insufficient)
}

/**
 * Thresholds for [SpeedVerdict]. A sustained sequential test is the best case: during a recording the drive also sees
 * container metadata, interleaved writes of several files, garbage collection of flash drives, and thermal throttling
 * of USB SSDs, hence ×2 for OK. Below ×1.25 there is no headroom even for those small extras.
 */
object WriteSpeedPolicy {
    const val OK_MARGIN = 2.0
    const val MIN_MARGIN = 1.25

    fun requiredBitsPerSecond(streams: RecordingStreams, margin: Double = OK_MARGIN): Double = streams.totalBitsPerSecond * margin

    fun classify(measuredBitsPerSecond: Double, streams: RecordingStreams): SpeedVerdict = when {
        measuredBitsPerSecond >= requiredBitsPerSecond(streams, OK_MARGIN) -> SpeedVerdict.OK
        measuredBitsPerSecond >= requiredBitsPerSecond(streams, MIN_MARGIN) -> SpeedVerdict.MARGINAL
        else -> SpeedVerdict.INSUFFICIENT
    }

    fun classify(result: WriteSpeedResult, streams: RecordingStreams): SpeedVerdict = classify(result.bitsPerSecond, streams)
}
