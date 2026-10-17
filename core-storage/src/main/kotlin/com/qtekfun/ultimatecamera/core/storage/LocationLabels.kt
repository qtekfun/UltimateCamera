package com.qtekfun.ultimatecamera.core.storage

import android.content.res.Resources

/** Short names of locations for the pickers and the status line, shared by the camera screen and Settings (D50). */
object LocationLabels {
    /** "Phone storage", the drive's system name (e.g. "SanDisk USB drive") or "Folder: Footage/Day 1". */
    @Suppress("SpreadOperator") // At most one format argument.
    fun location(resources: Resources, location: RecordingLocation, drives: List<Drive> = emptyList(), volume: VolumeInfo? = null): String =
        location({ id, args -> resources.getString(id, *args) }, location, drives, volume)

    /** [location] with [string] standing in for `Resources.getString(id, args)`. */
    fun location(string: (Int, Array<out Any>) -> String, location: RecordingLocation, drives: List<Drive> = emptyList(), volume: VolumeInfo? = null): String =
        when (location) {
            RecordingLocation.Default -> string(R.string.storage_mode_phone, emptyArray())
            is RecordingLocation.Volume -> {
                val info = volume ?: drives.firstOrNull { it.location == location }?.volume
                info?.description?.takeIf { it.isNotBlank() } ?: string(R.string.storage_drive_unnamed, emptyArray())
            }
            is RecordingLocation.Folder -> {
                val path = location.tree?.path.orEmpty().ifEmpty { string(R.string.storage_folder_root, emptyArray()) }
                string(R.string.storage_folder_named, arrayOf(path))
            }
        }
}
