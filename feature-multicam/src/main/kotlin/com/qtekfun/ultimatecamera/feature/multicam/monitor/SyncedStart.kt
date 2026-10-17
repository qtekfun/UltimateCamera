package com.qtekfun.ultimatecamera.feature.multicam.monitor

/**
 * When a "REC all" should start on every camera (REQ-NET-005). The synced-start service of core-net (common clock
 * source + scheduled start) plugs in here; without it, or while it cannot plan, every camera starts on arrival.
 */
fun interface SyncedStart {
    /**
     * Common-clock time (ns) at which the take should start on all cameras, given the control round trips measured to
     * each ([rttNanos], cameras without a measure left out); null starts immediately.
     */
    fun planStart(rttNanos: List<Long>): Long?

    companion object {
        /** No common clock: start on arrival (each camera's start is then up to one control round trip apart). */
        val IMMEDIATE = SyncedStart { null }
    }
}

/** The plan for one "REC all": the same [recordingId] on every camera, and the common start time or null (immediate). */
data class GroupStart(val recordingId: String, val startAtCommonNanos: Long?)

/** Pure planning of "REC all", unit-tested: a failing or absent synced-start service falls back to an immediate start. */
object GroupStartPlanner {
    /** Plug-in boundary: whatever the service throws, the take still starts (immediately). */
    fun plan(syncedStart: SyncedStart?, rttNanos: List<Long>, recordingId: String): GroupStart =
        GroupStart(recordingId, syncedStart?.let { runCatching { it.planStart(rttNanos) }.getOrNull() })
}
