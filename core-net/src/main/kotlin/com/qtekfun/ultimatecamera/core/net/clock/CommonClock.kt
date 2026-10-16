package com.qtekfun.ultimatecamera.core.net.clock

/**
 * The common clock of a multicamera session (REQ-NET-005): nanoseconds since the Unix epoch as kept by the clock source
 * (the master; in a camera + monitor pair without master, the monitor). Every device converts it to and from its own
 * monotonic clock, so a scheduled start or a timecode means the same instant on every phone.
 */
interface CommonClockView {
    /** Common time for local monotonic time [local]; null while not synced. */
    fun toCommon(local: Long): Long?

    /** Local monotonic time for common time [common]; null while not synced. */
    fun toLocal(common: Long): Long?

    /** Bound of the error of the conversion; null while not synced, 0 on the clock source. */
    val errorBoundNanos: Long?

    val isSynced: Boolean get() = errorBoundNanos != null
}

/**
 * The clock source's own view: its monotonic clock anchored once to wall-clock time, so the common clock never jumps
 * when the wall clock is adjusted (NTP, time zone, user) during a session.
 */
class SourceClock(private val monotonic: MonotonicClock, epochNanosAtStart: Long) : CommonClockView {
    private val anchorLocal = monotonic.nanos()
    private val anchorCommon = epochNanosAtStart

    fun now(): Long = toCommon(monotonic.nanos())

    override fun toCommon(local: Long): Long = anchorCommon + (local - anchorLocal)

    override fun toLocal(common: Long): Long = anchorLocal + (common - anchorCommon)

    override val errorBoundNanos: Long = 0L
}

/** A follower's view, from the [ClockEstimator] fed by the clock client. Thread-safe. */
class SyncedClock(private val estimator: ClockEstimator, private val minSamples: Int = ClockEstimator.DEFAULT_MIN_SAMPLES) : CommonClockView {
    private var cached: ClockEstimate? = null
    private var cachedRevision = -1L

    val estimate: ClockEstimate?
        @Synchronized get() {
            if (cachedRevision != estimator.revision) {
                cached = estimator.estimate(minSamples)
                cachedRevision = estimator.revision
            }
            return cached
        }

    override fun toCommon(local: Long): Long? = estimate?.toServer(local)

    override fun toLocal(common: Long): Long? = estimate?.toClient(common)

    override val errorBoundNanos: Long? get() = estimate?.errorBoundNanos
}
