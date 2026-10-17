package com.qtekfun.ultimatecamera.core.storage

import android.content.res.Resources

/** Short names of locations for the pickers and the status line, shared by the camera screen and Settings (D50). */
object LocationLabels {
    /** "Phone storage", the drive's system name (e.g. "SanDisk USB drive") or "Folder: Footage/Day 1". */
    fun location(resources: Resources, location: RecordingLocation, drives: List<Drive> = emptyList(), volume: VolumeInfo? = null): String = when (location) {
        RecordingLocation.Default -> resources.getString(R.string.storage_mode_phone)
        is RecordingLocation.Volume -> {
            val info = volume ?: drives.firstOrNull { it.location == location }?.volume
            info?.description?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.storage_drive_unnamed)
        }
        is RecordingLocation.Folder -> {
            val path = location.tree?.path.orEmpty().ifEmpty { resources.getString(R.string.storage_folder_root) }
            resources.getString(R.string.storage_folder_named, path)
        }
    }
}
