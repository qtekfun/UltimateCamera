package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation

/** Why a take was stopped by the app rather than by the user. */
enum class StopReason {
    /** The phone reached `THERMAL_STATUS_CRITICAL` (or worse). */
    THERMAL,

    /** [RecordingGuard.LOW_BATTERY_PERCENT] or less and not charging. */
    BATTERY,

    /** Free space fell under a minute of recording at the take's bitrate. */
    STORAGE_FULL
}

/** What the guard wants done with the take in progress. */
sealed interface GuardAction {
    data object None : GuardAction

    /** `THERMAL_STATUS_SEVERE`: tell the user once per take; the take goes on. */
    data object WarnThermal : GuardAction

    /** The current part reached the split size: continue in the next file. */
    data object Split : GuardAction

    /** Finish the take now (file saved) and say why. */
    data class Stop(val reason: StopReason) : GuardAction
}

/**
 * Decides, a couple of times per second while recording, whether a take must be stopped, split or warned about
 * (decision D47). Pure and allocation-free per call: [RecordingMonitor] feeds it the phone's state.
 *
 * Priorities: heat > battery > storage > split > heat warning. A stop is reported once per take; so are the warning
 * and each split (a new split is possible only after the current part became small again, i.e. the split happened).
 */
class RecordingGuard {
    private var splitLimitBytes: Long? = null
    private var stopped = false
    private var warned = false
    private var splitRequested = false

    /** A new take begins; [splitLimitBytes] is the part size limit, null for no splitting ([splitLimit]). */
    fun reset(splitLimitBytes: Long?) {
        this.splitLimitBytes = splitLimitBytes
        stopped = false
        warned = false
        splitRequested = false
    }

    /**
     * @param thermalStatus `PowerManager.THERMAL_STATUS_*`.
     * @param batteryPercent 0..100, or negative when unknown.
     * @param storageFull the last free-space check found less than a minute left.
     * @param partBytes size of the current part's file, or negative when unknown.
     */
    fun decide(thermalStatus: Int, batteryPercent: Int, charging: Boolean, storageFull: Boolean, partBytes: Long): GuardAction {
        if (stopped) return GuardAction.None
        val stop = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> StopReason.THERMAL
            batteryPercent in 0..LOW_BATTERY_PERCENT && !charging -> StopReason.BATTERY
            storageFull -> StopReason.STORAGE_FULL
            else -> null
        }
        if (stop != null) {
            stopped = true
            return GuardAction.Stop(stop)
        }
        val limit = splitLimitBytes
        if (limit != null) {
            if (partBytes in 0 until limit) {
                splitRequested = false
            } else if (partBytes >= limit && !splitRequested) {
                splitRequested = true
                return GuardAction.Split
            }
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && !warned) {
            warned = true
            return GuardAction.WarnThermal
        }
        return GuardAction.None
    }

    companion object {
        /** At 3 % (and not charging) the take is finished while there is still power to write the MP4 index. */
        const val LOW_BATTERY_PERCENT = 3

        /**
         * Part size on volumes that may be FAT32 (4 GiB − 1 per file): 3.9 GiB leaves ~100 MiB for the index and for
         * what is written between two checks (at 400 Mbit/s, 0.5 s ≈ 25 MB).
         */
        const val FAT32_SPLIT_BYTES = 3_900L * 1024 * 1024 * 1024 / 1000

        /** Frames in flight between the camera and the encoder counter at any moment; not counted as dropped. */
        const val LATENCY_FRAMES = 3

        /**
         * Part size limit for [location]: none on the phone's own storage (ext4/f2fs, and MediaMuxer handles files over
         * 4 GB there), [FAT32_SPLIT_BYTES] on any other volume (SD cards and USB drives are often FAT32, and Android
         * does not tell the file system).
         */
        fun splitLimit(location: RecordingLocation?): Long? = when (location) {
            null, RecordingLocation.Default -> null
            is RecordingLocation.Folder -> if (location.tree?.isPrimaryVolume == true) null else FAT32_SPLIT_BYTES
        }

        /** Frames the camera should have delivered in [elapsedMs] at [fps] minus those encoded, never negative. */
        fun droppedFrames(elapsedMs: Long, fps: Int, encodedFrames: Int): Int {
            val expected = elapsedMs * fps / MILLIS_PER_SECOND
            return (expected - encodedFrames - LATENCY_FRAMES).coerceAtLeast(0).toInt()
        }

        /** Average bitrate of [bytes] written over [millis]; 0 for an empty interval. */
        fun bitsPerSecond(bytes: Long, millis: Long): Long = if (millis <= 0) 0 else bytes * Byte.SIZE_BITS * MILLIS_PER_SECOND / millis

        private const val MILLIS_PER_SECOND = 1000L
    }
}
