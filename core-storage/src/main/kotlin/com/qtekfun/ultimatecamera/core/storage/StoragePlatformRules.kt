package com.qtekfun.ultimatecamera.core.storage

import android.os.Environment
import java.io.IOException

/** The MediaStore collections the app writes to, each with its folder under the volume (D27). */
enum class MediaKind(val relativePath: String) {
    VIDEO(RecordingLocation.Default.VIDEO_RELATIVE_PATH),
    AUDIO(RecordingLocation.Default.AUDIO_RELATIVE_PATH),
    IMAGE(RecordingLocation.Default.PHOTO_RELATIVE_PATH)
}

/** Where [AndroidStorageBackend.freeBytes] reads a location's free space from. */
sealed interface FreeSpaceSource {
    /** The documents provider's root of this SAF volume id, with `StatFs` of the same volume as fallback. */
    data class DocumentsRoot(val volumeId: String) : FreeSpaceSource

    /** `StatFs` of this MediaStore volume (a removable drive needs no documents root). */
    data class Volume(val volumeName: String) : FreeSpaceSource

    /** Not on a local volume (e.g. a cloud provider's folder): unknown. */
    data object Unknown : FreeSpaceSource
}

/**
 * The decisions of [AndroidStorageBackend] that do not need the platform: which MediaStore collection and folder a
 * file goes to, how volumes are matched, where free space comes from, the query selections, and how platform failures
 * map to the [StorageBackend] contract ([IOException]).
 */
object StoragePlatformRules {
    /** Selection of a location's files in one collection (pending rows included). */
    const val SELECT_BY_FOLDER = "relative_path = ?"

    /** Selection of one file by name in a location's folder of one collection (pending rows included). */
    const val SELECT_BY_NAME_IN_FOLDER = "_display_name = ? AND relative_path = ?"

    /** The collection a file of [mimeType] goes to in the default location or on a drive; other types are refused. */
    @Throws(IOException::class)
    fun mediaKind(mimeType: String): MediaKind = when {
        mimeType.startsWith("video/") -> MediaKind.VIDEO
        mimeType.startsWith("audio/") -> MediaKind.AUDIO
        mimeType.startsWith("image/") -> MediaKind.IMAGE
        else -> throw IOException("the default location takes video, audio or images, not $mimeType")
    }

    /** The MediaStore volume of a MediaStore location: the drive's, or [primary] for the phone's own storage. */
    fun mediaStoreVolume(location: RecordingLocation, primary: String): String = (location as? RecordingLocation.Volume)?.volumeName ?: primary

    fun freeSpaceSource(location: RecordingLocation): FreeSpaceSource = when (location) {
        RecordingLocation.Default -> FreeSpaceSource.DocumentsRoot(SafTree.PRIMARY_VOLUME_ID)
        is RecordingLocation.Folder -> location.tree?.volumeId?.let { FreeSpaceSource.DocumentsRoot(it) } ?: FreeSpaceSource.Unknown
        is RecordingLocation.Volume -> FreeSpaceSource.Volume(location.volumeName)
    }

    /**
     * True when a volume with [uuid] and MediaStore name [mediaStoreName] is the one [volumeId] names: SAF ids use the
     * UUID in upper case, MediaStore names it in lower case.
     */
    fun volumeMatches(volumeId: String, uuid: String?, mediaStoreName: String?): Boolean =
        uuid.equals(volumeId, ignoreCase = true) || mediaStoreName == volumeId

    /** `StorageVolume.getState()` values a recording can use. */
    fun mounted(state: String?): Boolean = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    fun readOnly(state: String?): Boolean = state == Environment.MEDIA_MOUNTED_READ_ONLY

    /** Free bytes of [volumeId] among the documents provider's roots (root id, available bytes or null), first match. */
    fun rootAvailableBytes(roots: Sequence<Pair<String?, Long?>>, volumeId: String): Long? =
        roots.firstOrNull { (id, bytes) -> id.equals(volumeId, ignoreCase = true) && bytes != null }?.second

    /** The removable drives among MediaStore's external volume [names]: neither of the primary aliases, sorted. */
    fun removableVolumeNames(names: Collection<String>, primaryAliases: Set<String>, isRemovable: (String) -> Boolean): List<String> =
        names.filter { it !in primaryAliases }.filter(isRemovable).sorted()

    /** Document ids of the children ([id] to display name) called [displayName]: SAF allows duplicates. */
    fun documentsNamed(children: List<Pair<String, String?>>, displayName: String): List<String> = children.filter { it.second == displayName }.map { it.first }

    /** Platform calls report failures as several unchecked exceptions; the [StorageBackend] contract is [IOException]. */
    inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw IOException(e.message, e)
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    } catch (e: IllegalStateException) {
        throw IOException(e.message, e)
    } catch (e: UnsupportedOperationException) {
        throw IOException(e.message, e)
    }
}
