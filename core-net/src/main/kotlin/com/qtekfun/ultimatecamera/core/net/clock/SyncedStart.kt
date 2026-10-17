package com.qtekfun.ultimatecamera.core.net.clock

/**
 * The camera's frame clock against the clock of the common-clock code (REQ-NET-005). The clock client and the camera
 * node run on `elapsedRealtimeNanos` ([local]); camera frames are stamped in the boot-time clock on
 * `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` sensors and in `System.nanoTime` on `UNKNOWN` ones ([camera]). Both tick at
 * the same rate while the phone is awake, so one offset read now converts any instant (error: the few microseconds
 * between the two reads). For a REALTIME sensor pass the same clock twice: the conversion is then exact.
 */
class CameraClockBridge(private val local: MonotonicClock, private val camera: MonotonicClock) {
    /** camera − local, read now. */
    fun offsetNanos(): Long {
        if (local === camera) return 0
        val before = local.nanos()
        val cam = camera.nanos()
        val after = local.nanos()
        return cam - (before + (after - before) / 2)
    }

    fun toCamera(localNanos: Long): Long = localNanos + offsetNanos()

    fun toLocal(cameraNanos: Long): Long = cameraNanos - offsetNanos()

    fun cameraNow(): Long = camera.nanos()
}

/**
 * Wall time (Unix epoch nanoseconds) of instants of the camera clock, for the time-of-day timecode of a take: the
 * session's common clock while it is synced (every camera then labels the same instant alike), else the phone's own
 * wall clock read now (a single phone; NTP keeps it within tens of milliseconds of other phones, not frame-exact).
 */
class TimeOfDay(private val bridge: CameraClockBridge, private val common: () -> CommonClockView?, private val wallNanos: () -> Long) {
    /** True when [epochNanos] comes from a synced common clock. */
    val isCommon: Boolean get() = common()?.isSynced == true

    fun epochNanos(cameraNanos: Long): Long {
        val local = bridge.toLocal(cameraNanos)
        common()?.toCommon(local)?.let { return it }
        return wallNanos() + (cameraNanos - bridge.cameraNow())
    }
}

/**
 * Synchronised start of a multicamera take (REQ-NET-005, D31, D58), on top of [ScheduledStart]: the master (or the
 * monitor in a two-phone setup) plans a common instant on a timecode frame boundary and sends it; each camera converts
 * it to its camera clock and arms its recorder at once (encoder attached, frames encoded only from the first one
 * stamped at or after the instant), so every camera's first frame falls within one frame of the others. A camera that
 * gets the command after the instant starts at once and reports how late it is.
 */
object SyncedStart {
    /** The instant to send, from the common time [nowCommon] and each camera's control round trip [rttsNanos]. */
    fun plan(nowCommon: Long, rttsNanos: Collection<Long>, rate: FrameRate?, utcOffsetSeconds: Int): Long =
        ScheduledStart.plan(nowCommon, ScheduledStart.margin(rttsNanos), rate, utcOffsetSeconds)

    /** What a camera does with a start at common time [atCommon]. */
    sealed interface Arm {
        /** Arm the recorder for camera-clock time [cameraNanos] (still ahead). */
        data class At(val cameraNanos: Long) : Arm

        /** The instant has passed: start now ([cameraNanos] is the camera clock now), [lateNanos] behind the others. */
        data class Late(val cameraNanos: Long, val lateNanos: Long) : Arm

        data object TooFar : Arm

        data object NotSynced : Arm
    }

    /** Converts [atCommon] through [clock] (local monotonic time [nowLocal]) and [bridge] to the camera clock. */
    fun arm(atCommon: Long, clock: CommonClockView, bridge: CameraClockBridge, nowLocal: Long): Arm =
        when (val local = ScheduledStart.toLocal(atCommon, clock, nowLocal)) {
            ScheduledStart.Local.NotSynced -> Arm.NotSynced
            ScheduledStart.Local.TooFar -> Arm.TooFar
            is ScheduledStart.Local.Late -> Arm.Late(bridge.cameraNow(), local.lateNanos)
            is ScheduledStart.Local.At -> Arm.At(bridge.toCamera(local.localNanos))
        }

    /** Time-of-day timecode label of the first frame of a take, stamped [epochNanos] (from [TimeOfDay]). */
    fun timecode(epochNanos: Long, rate: FrameRate, utcOffsetSeconds: Int): Timecode = Timecode.ofTimeOfDay(epochNanos, utcOffsetSeconds, rate)
}

/**
 * The common clock of the multicamera session this phone takes part in, or null outside a session. Set by whoever runs
 * the clock (the camera role's clock client, or the master's [SourceClock]); read by the camera screen for the
 * time-of-day timecode, so the display and the takes use one source.
 */
object ActiveCommonClock {
    @Volatile var current: CommonClockView? = null
}
