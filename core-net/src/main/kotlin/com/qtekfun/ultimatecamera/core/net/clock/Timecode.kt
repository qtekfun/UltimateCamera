package com.qtekfun.ultimatecamera.core.net.clock

import kotlin.math.ceil
import kotlinx.serialization.Serializable

/** A frame rate as a fraction, e.g. 30000/1001 for 29.97. */
@Serializable
data class FrameRate(val numerator: Int, val denominator: Int = 1) {
    init {
        require(numerator > 0 && denominator > 0)
    }

    /** Frames counted per timecode second (30 for 29.97). */
    val nominal: Int get() = ceil(numerator.toDouble() / denominator).toInt()

    /** 29.97 and 59.94 may use drop-frame labels. */
    val supportsDropFrame: Boolean get() = denominator == NTSC_DEN && (numerator == NTSC_30 || numerator == NTSC_60)

    /**
     * Frame index containing [nanos] (floor) of a span starting at 0. Microsecond precision; meant for spans up to a day
     * (no overflow up to 240 fps), not for absolute epoch times.
     */
    fun framesIn(nanos: Long): Long = Math.floorDiv(Math.floorDiv(nanos, NANOS_PER_MICRO) * numerator, denominator.toLong() * MICROS_PER_S)

    /** Start of frame [frame] of a span, rounded up to the microsecond; `framesIn(startOf(f)) == f`. */
    fun startOf(frame: Long): Long = -Math.floorDiv(-frame * denominator * MICROS_PER_S, numerator.toLong()) * NANOS_PER_MICRO

    companion object {
        val FPS_23_976 = FrameRate(24_000, 1_001)
        val FPS_24 = FrameRate(24)
        val FPS_25 = FrameRate(25)
        val FPS_29_97 = FrameRate(30_000, 1_001)
        val FPS_30 = FrameRate(30)
        val FPS_50 = FrameRate(50)
        val FPS_59_94 = FrameRate(60_000, 1_001)
        val FPS_60 = FrameRate(60)

        private const val NTSC_DEN = 1_001
        private const val NTSC_30 = 30_000
        private const val NTSC_60 = 60_000
        private const val NANOS_PER_MICRO = 1_000L
        private const val MICROS_PER_S = 1_000_000L
    }
}

/**
 * SMPTE-style time-of-day timecode from the common clock (REQ-NET-005): every camera of a take labels the same instant
 * with the same timecode, written in each clip's metadata. `HH:MM:SS:FF`, or `HH:MM:SS;FF` for drop-frame.
 *
 * Frames are counted since local midnight at the real rate. Integer rates match the wall clock exactly. 29.97/59.94
 * drop-frame skips labels ;00 and ;01 (;00–;03 at 59.94) each minute except every tenth, staying within ~3 frames a day
 * of the wall clock. Non-drop fractional rates (23.976, 29.97 NDF) count with the nominal base, as cameras do, so their
 * labels run 0.1 % slower than the wall clock; the clip's start label is still identical on every phone.
 */
data class Timecode(val hours: Int, val minutes: Int, val seconds: Int, val frames: Int, val dropFrame: Boolean) {
    override fun toString(): String = "%02d:%02d:%02d%s%02d".format(hours, minutes, seconds, if (dropFrame) ";" else ":", frames)

    companion object {
        private const val NANOS_PER_S = 1_000_000_000L
        private const val SECONDS_PER_DAY = 86_400L
        private const val S_PER_MIN = 60
        private const val MIN_PER_H = 60
        private const val H_PER_DAY = 24
        private const val MINUTES_PER_BLOCK = 10

        /** Timecode of common time [commonNanos] (Unix epoch) in a zone [utcOffsetSeconds] east of UTC. */
        fun ofTimeOfDay(commonNanos: Long, utcOffsetSeconds: Int, rate: FrameRate, dropFrame: Boolean = rate.supportsDropFrame): Timecode {
            val dayNanos = SECONDS_PER_DAY * NANOS_PER_S
            val sinceMidnight = Math.floorMod(commonNanos + utcOffsetSeconds * NANOS_PER_S, dayNanos)
            return ofFrames(rate.framesIn(sinceMidnight), rate, dropFrame)
        }

        /** Label of frame number [frame] (0 = 00:00:00:00), wrapping at 24 h. */
        fun ofFrames(frame: Long, rate: FrameRate, dropFrame: Boolean = rate.supportsDropFrame): Timecode {
            require(frame >= 0)
            require(!dropFrame || rate.supportsDropFrame) { "drop-frame needs 29.97 or 59.94" }
            val base = rate.nominal
            var label = frame
            if (dropFrame) {
                val drop = base / 15 // 2 at 29.97, 4 at 59.94
                val perMinute = base * S_PER_MIN - drop
                val perTen = perMinute * MINUTES_PER_BLOCK + drop
                val tens = label / perTen
                val rest = label % perTen
                label += (MINUTES_PER_BLOCK - 1) * drop * tens + if (rest > drop) drop * ((rest - drop) / perMinute) else 0
            }
            val totalSeconds = label / base
            return Timecode(
                hours = ((totalSeconds / (S_PER_MIN * MIN_PER_H)) % H_PER_DAY).toInt(),
                minutes = ((totalSeconds / S_PER_MIN) % MIN_PER_H).toInt(),
                seconds = (totalSeconds % S_PER_MIN).toInt(),
                frames = (label % base).toInt(),
                dropFrame = dropFrame
            )
        }
    }
}

/**
 * Scheduled simultaneous start (REQ-NET-005): the master picks a common time far enough ahead for the command to reach
 * every camera, each camera converts it to its own monotonic clock and starts its recorder then.
 */
object ScheduledStart {
    /** Never less: covers command delivery, recorder warm-up and a Wi-Fi power-save wake-up. */
    const val MIN_MARGIN_NANOS = 300_000_000L

    /** Never more: a start further than this would feel unresponsive and is likely a bug or a bad clock. */
    const val MAX_MARGIN_NANOS = 3_000_000_000L

    /** A camera refuses a start more than this far in the future (protects against a broken clock). */
    const val MAX_AHEAD_NANOS = 60_000_000_000L

    /**
     * Margin for a start given each camera's control round trip [rttsNanos] (heartbeat measured): two worst round trips
     * (command plus a retransmission) on top of [MIN_MARGIN_NANOS], capped at [MAX_MARGIN_NANOS].
     */
    fun margin(rttsNanos: Collection<Long>): Long = (MIN_MARGIN_NANOS + 2 * (rttsNanos.maxOrNull() ?: 0L)).coerceAtMost(MAX_MARGIN_NANOS)

    /**
     * Start time: [nowCommon] + [marginNanos], rounded up to the next frame boundary of the time-of-day timecode at [rate]
     * (in the zone [utcOffsetSeconds] east of UTC, as [Timecode.ofTimeOfDay]), so every camera's first frame starts
     * exactly on the same timecode label. Without [rate] no rounding.
     */
    fun plan(nowCommon: Long, marginNanos: Long, rate: FrameRate? = null, utcOffsetSeconds: Int = 0): Long {
        val at = nowCommon + marginNanos
        if (rate == null) return at
        val dayStart = at - Math.floorMod(at + utcOffsetSeconds * NANOS_PER_S, DAY_NANOS)
        val frame = rate.framesIn(at - dayStart)
        val start = dayStart + rate.startOf(frame)
        return if (start >= at) start else dayStart + rate.startOf(frame + 1)
    }

    private const val NANOS_PER_S = 1_000_000_000L
    private const val DAY_NANOS = 86_400L * NANOS_PER_S

    /** Where a camera stands with a scheduled start. */
    sealed interface Local {
        /** Start at local monotonic time [localNanos] (in the future). */
        data class At(val localNanos: Long) : Local

        /** The time has passed (the command arrived late): start now, [lateNanos] behind the others. */
        data class Late(val lateNanos: Long) : Local

        /** Too far ahead: refuse. */
        data object TooFar : Local

        /** No common clock yet. */
        data object NotSynced : Local
    }

    fun toLocal(atCommon: Long, clock: CommonClockView, nowLocal: Long): Local {
        val local = clock.toLocal(atCommon) ?: return Local.NotSynced
        return when {
            local - nowLocal > MAX_AHEAD_NANOS -> Local.TooFar
            local <= nowLocal -> Local.Late(nowLocal - local)
            else -> Local.At(local)
        }
    }
}
