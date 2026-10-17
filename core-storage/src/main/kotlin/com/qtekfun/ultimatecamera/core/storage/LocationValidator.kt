package com.qtekfun.ultimatecamera.core.storage

import java.io.IOException

/**
 * Checks that a [RecordingLocation] can take a recording right now: local provider, grant still held, volume mounted,
 * folder present, and a real file can be created there (the probe is deleted at once). Blocking: call off the main
 * thread. Never throws for an unusable location; the problem is in the result.
 */
class LocationValidator(private val backend: StorageBackend) {
    fun validate(location: RecordingLocation): LocationStatus {
        val volume = volumeOf(location)
        val problem = (location as? RecordingLocation.Folder)?.let { folderProblem(it, volume) } ?: when {
            location is RecordingLocation.Volume && (volume == null || !volume.mounted) -> LocationProblem.VOLUME_UNMOUNTED
            volume?.readOnly == true -> LocationProblem.NOT_WRITABLE
            !backend.folderExists(location) -> LocationProblem.FOLDER_MISSING
            !canCreateFile(location) -> LocationProblem.NOT_WRITABLE
            else -> null
        }
        return if (problem == null) LocationStatus.Ready(volume, backend.freeBytes(location)) else LocationStatus.Unavailable(problem)
    }

    private fun volumeOf(location: RecordingLocation): VolumeInfo? = when (location) {
        RecordingLocation.Default -> backend.volume(SafTree.PRIMARY_VOLUME_ID)
        is RecordingLocation.Folder -> location.tree?.volumeId?.let(backend::volume)
        is RecordingLocation.Volume -> backend.volume(location.volumeName)
    }

    /** The checks only a picked folder needs, before the common ones. */
    private fun folderProblem(folder: RecordingLocation.Folder, volume: VolumeInfo?): LocationProblem? = when {
        folder.tree?.isLocalStorage != true -> LocationProblem.UNSUPPORTED_PROVIDER
        !backend.hasPersistedPermission(folder.treeUri) -> LocationProblem.PERMISSION_LOST
        volume == null || !volume.mounted -> LocationProblem.VOLUME_UNMOUNTED
        else -> null
    }

    private fun canCreateFile(location: RecordingLocation): Boolean = try {
        backend.deleteIfExists(location, PROBE_NAME)
        backend.createFile(location, PROBE_NAME, PROBE_MIME_TYPE, temporary = true).delete()
        true
    } catch (_: IOException) {
        false
    }

    companion object {
        /** Zero-byte probe file. Video MIME type because the default location (MediaStore `Movies/`) only takes video. */
        const val PROBE_NAME = ".ultimatecamera-probe.mp4"
        const val PROBE_MIME_TYPE = "video/mp4"
    }
}
