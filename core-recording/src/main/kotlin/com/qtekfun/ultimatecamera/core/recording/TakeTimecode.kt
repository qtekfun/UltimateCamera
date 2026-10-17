package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.audio.BextChunk
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Where a take's time-of-day timecode is written (D58). MediaMuxer cannot write a QuickTime `tmcd` track, so the
 * carrier editors read is the take's WAV as Broadcast Wave: `bext` TimeReference = samples since local midnight of the
 * first sample, which is the take's start instant (sample-accurate, the video's first frame follows within one frame).
 */
object TakeTimecode {
    private const val NANOS_PER_S = 1_000_000_000L
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Offset east of UTC, in seconds, of [zone] at [epochNanos] (summer time included). */
    fun utcOffsetSeconds(epochNanos: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        zone.rules.getOffset(Instant.ofEpochSecond(Math.floorDiv(epochNanos, NANOS_PER_S))).totalSeconds

    /** The `bext` chunk of a take whose first sample is at [epochNanos], named [clipName]. */
    fun bext(epochNanos: Long, sampleRate: Int, clipName: String, zone: ZoneId = ZoneId.systemDefault()): BextChunk {
        val offset = utcOffsetSeconds(epochNanos, zone)
        val local = LocalDateTime.ofEpochSecond(Math.floorDiv(epochNanos, NANOS_PER_S), 0, ZoneOffset.ofTotalSeconds(offset))
        return BextChunk(
            description = "UltimateCamera take $clipName",
            originatorReference = clipName.substringBeforeLast('.'),
            originationDate = DATE.format(local),
            originationTime = TIME.format(local),
            timeReferenceSamples = BextChunk.samplesSinceMidnight(epochNanos, offset, sampleRate)
        )
    }
}
