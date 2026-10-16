package com.qtekfun.ultimatecamera.core.storage

import androidx.annotation.StringRes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How much recording time is left, for the status line and the pre-recording check. */
enum class FreeSpaceStatus(@StringRes val messageRes: Int?) {
    OK(null),

    /** Less than [RecordingTimeEstimate.LOW_THRESHOLD] left. */
    LOW(R.string.storage_space_low),

    /** Less than [RecordingTimeEstimate.FULL_THRESHOLD] left: do not start a recording. */
    FULL(R.string.storage_space_full)
}

/**
 * Estimates recording time from free space. [RESERVE_BYTES] stays free (Android misbehaves on a full internal
 * storage, and the muxer needs room to finalise the file), and [CONTAINER_OVERHEAD] covers MP4 metadata and indexes.
 */
object RecordingTimeEstimate {
    const val RESERVE_BYTES = 256L * 1024 * 1024
    const val CONTAINER_OVERHEAD = 1.02
    val LOW_THRESHOLD: Duration = 10.minutes
    val FULL_THRESHOLD: Duration = 1.minutes

    /** Time left at [streams]' bitrate with [freeBytes] free; [Duration.ZERO] if nothing usable is left. */
    fun remaining(freeBytes: Long, streams: RecordingStreams): Duration {
        val usableBits = (freeBytes - RESERVE_BYTES).coerceAtLeast(0) * Byte.SIZE_BITS.toDouble()
        val seconds = usableBits / (streams.totalBitsPerSecond * CONTAINER_OVERHEAD)
        return seconds.toLong().seconds
    }

    /** Whole minutes left, rounded down (what the UI shows). */
    fun remainingMinutes(freeBytes: Long, streams: RecordingStreams): Long = remaining(freeBytes, streams).inWholeMinutes

    fun classify(remaining: Duration): FreeSpaceStatus = when {
        remaining < FULL_THRESHOLD -> FreeSpaceStatus.FULL
        remaining < LOW_THRESHOLD -> FreeSpaceStatus.LOW
        else -> FreeSpaceStatus.OK
    }

    fun classify(freeBytes: Long, streams: RecordingStreams): FreeSpaceStatus = classify(remaining(freeBytes, streams))
}
