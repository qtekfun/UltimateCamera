package com.qtekfun.ultimatecamera.core.codec.mp4

import java.util.Locale
import kotlin.math.ceil

/**
 * The start of a take's QuickTime timecode track (D58, D69): [frame] = frames since local midnight at [numerator] /
 * [denominator] frames per second (the sample of the `tmcd` track), counted at the real rate as the screen and the BWF
 * do. 29.97 and 59.94 are labelled drop-frame ([dropFrame]); editors turn the counter into `HH:MM:SS;FF` themselves.
 */
data class TimecodeStart(val frame: Long, val numerator: Int, val denominator: Int = 1, val dropFrame: Boolean = supportsDropFrame(numerator, denominator)) {
    init {
        require(frame >= 0 && numerator > 0 && denominator > 0) { "bad timecode $frame @ $numerator/$denominator" }
        require(!dropFrame || supportsDropFrame(numerator, denominator)) { "drop-frame needs 29.97 or 59.94" }
    }

    /** Frames per timecode second (30 for 29.97), the `numberOfFrames` of the sample description. */
    val nominal: Int get() = nominal(numerator, denominator)

    /** `HH:MM:SS:FF` (or `;FF` for drop-frame) of [frame], wrapping at 24 h, as editors show it. */
    val label: String get() = label(frame, numerator, denominator, dropFrame)

    companion object {
        private const val NANOS_PER_S = 1_000_000_000L
        private const val NANOS_PER_MICRO = 1_000L
        private const val MICROS_PER_S = 1_000_000L
        private const val DAY_NANOS = 86_400L * NANOS_PER_S
        private const val NTSC_DEN = 1_001
        private const val NTSC_30 = 30_000
        private const val NTSC_60 = 60_000
        private const val S_PER_MIN = 60
        private const val MIN_PER_H = 60
        private const val H_PER_DAY = 24
        private const val MINUTES_PER_BLOCK = 10
        private const val DROP_DIVISOR = 15

        fun supportsDropFrame(numerator: Int, denominator: Int): Boolean = denominator == NTSC_DEN && (numerator == NTSC_30 || numerator == NTSC_60)

        fun nominal(numerator: Int, denominator: Int): Int = ceil(numerator.toDouble() / denominator).toInt()

        /**
         * The timecode of a take whose first frame is at [epochNanos] (Unix epoch) in a zone [utcOffsetSeconds] east of
         * UTC: frames since local midnight (floor, microsecond precision), the same count as the on-screen timecode.
         */
        fun ofTimeOfDay(epochNanos: Long, utcOffsetSeconds: Int, numerator: Int, denominator: Int = 1): TimecodeStart {
            val sinceMidnight = Math.floorMod(epochNanos + utcOffsetSeconds * NANOS_PER_S, DAY_NANOS)
            val frame = Math.floorDiv(Math.floorDiv(sinceMidnight, NANOS_PER_MICRO) * numerator, denominator.toLong() * MICROS_PER_S)
            return TimecodeStart(frame, numerator, denominator)
        }

        /** SMPTE label of frame counter [frame]; drop-frame skips ;00/;01 (;00–;03 at 59.94) each minute but every tenth. */
        fun label(frame: Long, numerator: Int, denominator: Int, dropFrame: Boolean): String {
            val base = nominal(numerator, denominator)
            var count = frame
            if (dropFrame) {
                val drop = base / DROP_DIVISOR
                val perMinute = base * S_PER_MIN - drop
                val perTen = perMinute * MINUTES_PER_BLOCK + drop
                val tens = count / perTen
                val rest = count % perTen
                count += (MINUTES_PER_BLOCK - 1) * drop * tens + if (rest > drop) drop * ((rest - drop) / perMinute) else 0
            }
            val seconds = count / base
            val h = (seconds / (S_PER_MIN * MIN_PER_H)) % H_PER_DAY
            val m = (seconds / S_PER_MIN) % MIN_PER_H
            val s = seconds % S_PER_MIN
            return String.format(Locale.ROOT, "%02d:%02d:%02d%s%02d", h, m, s, if (dropFrame) ";" else ":", count % base)
        }
    }
}
