package com.qtekfun.ultimatecamera.core.storage

import androidx.annotation.StringRes

/** Result of validating a [RecordingLocation] before recording. */
sealed interface LocationStatus {
    /**
     * Recording can start.
     *
     * @property volume the volume the location is on; null if the system did not report it.
     * @property freeBytes free space on that volume; null if unknown.
     */
    data class Ready(val volume: VolumeInfo?, val freeBytes: Long?) : LocationStatus

    data class Unavailable(val problem: LocationProblem) : LocationStatus
}

/** Why a location cannot be used, with the text the UI shows. [LocationValidator] checks them in this order. */
enum class LocationProblem(@StringRes val messageRes: Int) {
    /** Not a folder on a local volume (e.g. a cloud provider): streaming video there is not reliable. */
    UNSUPPORTED_PROVIDER(R.string.storage_problem_unsupported_provider),

    /** The persisted SAF grant is gone (revoked, app data cleared, or a backup restored on another phone). */
    PERMISSION_LOST(R.string.storage_problem_permission_lost),

    /** The SD card or USB drive is not connected or not mounted. */
    VOLUME_UNMOUNTED(R.string.storage_problem_volume_unmounted),

    /** The folder was deleted or renamed. */
    FOLDER_MISSING(R.string.storage_problem_folder_missing),

    /** A test file could not be created (read-only volume, full, provider error). */
    NOT_WRITABLE(R.string.storage_problem_not_writable)
}

/**
 * Outcome of [RecordingLocationRepository.check].
 *
 * @property chosen what the user picked ([RecordingLocation.Default] if nothing).
 * @property status the chosen location's validation.
 * @property effective where the next recording goes: [chosen] if ready, otherwise [RecordingLocation.Default] when
 *   that is ready, otherwise null (nowhere to record).
 * @property effectiveStatus validation of [effective]; the same as [status] unless the app fell back.
 * @property mode what the user asked for; [chosen] is its resolution (D50).
 */
data class LocationCheck(
    val chosen: RecordingLocation,
    val status: LocationStatus,
    val effective: RecordingLocation?,
    val effectiveStatus: LocationStatus,
    val mode: LocationMode = LocationMode.Automatic
) {
    /** The chosen folder is unusable and recordings go to the default location: the UI must say so. */
    val fellBack: Boolean get() = effective != null && effective != chosen
}
