package com.qtekfun.ultimatecamera.core.storage

import androidx.annotation.StringRes
import java.io.ByteArrayOutputStream

/** Where recordings go (REQ-STO-001). */
sealed interface RecordingLocation {
    /** A folder the user picked with `ACTION_OPEN_DOCUMENT_TREE`; the app holds a persisted read/write grant on it. */
    data class Folder(val treeUri: String) : RecordingLocation {
        /** The parsed tree, or null when [treeUri] is not a SAF tree URI. */
        val tree: SafTree? get() = SafTree.parse(treeUri)
    }

    /**
     * Used while no folder is chosen (decision D27 in docs/PROGRESS.md): MediaStore on the primary shared storage,
     * video in [VIDEO_RELATIVE_PATH], audio sidecars (separate WAV, REQ-AUD-003) in [AUDIO_RELATIVE_PATH] because
     * MediaStore only accepts video in `Movies/`. No permission is needed, and clips survive an uninstall.
     */
    data object Default : RecordingLocation {
        const val VIDEO_RELATIVE_PATH = "Movies/UltimateCamera/"
        const val AUDIO_RELATIVE_PATH = "Recordings/UltimateCamera/"

        /** Photos (decision D41): MediaStore only accepts images in `Pictures/` or `DCIM/`. */
        const val PHOTO_RELATIVE_PATH = "Pictures/UltimateCamera/"
    }

    companion object {
        /** The location for a stored tree URI (core-settings' `recordingTreeUri`); null or blank is [Default]. */
        fun fromStored(treeUri: String?): RecordingLocation = if (treeUri.isNullOrBlank()) Default else Folder(treeUri)
    }
}

/**
 * A SAF tree URI split into its parts, without `android.net.Uri` so it is testable on the JVM.
 *
 * For the system's local storage provider ([EXTERNAL_STORAGE_AUTHORITY]) the document id is `<volume>:<path>`, where
 * `<volume>` is `primary` for the built-in shared storage or the file-system UUID of an SD card or USB drive (the same
 * value as `StorageVolume.getUuid()`).
 */
data class SafTree(val authority: String, val documentId: String) {
    /** `primary` or the volume UUID for [EXTERNAL_STORAGE_AUTHORITY]; null for other providers. */
    val volumeId: String?
        get() = if (authority == EXTERNAL_STORAGE_AUTHORITY) documentId.substringBefore(':', "").ifEmpty { null } else null

    /** True when the tree lives on a local volume the app can query and validate. */
    val isLocalStorage: Boolean get() = volumeId != null

    /** Folder path inside the volume ("" for its root). */
    val path: String get() = documentId.substringAfter(':', "")

    val isPrimaryVolume: Boolean get() = volumeId == PRIMARY_VOLUME_ID

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_VOLUME_ID = "primary"
        private const val SCHEME = "content://"

        /** Parses `content://<authority>/tree/<encoded document id>[/document/...]`; null for anything else. */
        fun parse(uri: String): SafTree? {
            if (!uri.startsWith(SCHEME)) return null
            val rest = uri.substring(SCHEME.length)
            val authority = rest.substringBefore('/')
            val segments = rest.substringAfter('/', "").split('/')
            val treeIndex = segments.indexOf("tree")
            if (authority.isEmpty() || treeIndex < 0 || treeIndex + 1 >= segments.size) return null
            val documentId = percentDecode(segments[treeIndex + 1]) ?: return null
            return if (documentId.isEmpty()) null else SafTree(authority, documentId)
        }

        /** RFC 3986 percent-decoding as UTF-8 (`+` stays `+`, unlike `URLDecoder`); null on a malformed escape. */
        internal fun percentDecode(encoded: String): String? {
            val bytes = ByteArrayOutputStream(encoded.length)
            var i = 0
            while (i < encoded.length) {
                val c = encoded[i]
                if (c == '%') {
                    if (i + 2 >= encoded.length) return null
                    val value = encoded.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    bytes.write(value)
                    i += 3
                } else {
                    val utf8 = c.toString().toByteArray(Charsets.UTF_8)
                    bytes.write(utf8, 0, utf8.size)
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}

/** What kind of volume a location is on, for the label next to the folder name. */
enum class VolumeKind(@StringRes val labelRes: Int) {
    /** The phone's built-in shared storage. */
    INTERNAL(R.string.storage_volume_internal),

    /**
     * An SD card or a USB drive (e.g. a USB SSD). Android has no public API that tells them apart; the system's own
     * description ([VolumeInfo.description], e.g. "USB drive") is shown next to this label.
     */
    REMOVABLE(R.string.storage_volume_removable),

    /** Not a local volume (another documents provider). */
    UNKNOWN(R.string.storage_volume_unknown)
    ;

    companion object {
        /** Primary or emulated storage is internal; a volume Android marks removable is an SD card or USB drive. */
        fun classify(isPrimary: Boolean, isEmulated: Boolean, isRemovable: Boolean): VolumeKind = when {
            isPrimary || isEmulated -> INTERNAL
            isRemovable -> REMOVABLE
            else -> INTERNAL
        }
    }
}

/**
 * A storage volume as the system reports it.
 *
 * @property id [SafTree.PRIMARY_VOLUME_ID] or the volume's file-system UUID.
 * @property description the system's localised name, e.g. "SanDisk USB drive"; null when unknown.
 * @property mounted false while the volume is present but not usable (ejecting, checking, unmountable).
 * @property readOnly mounted read-only.
 */
data class VolumeInfo(val id: String, val kind: VolumeKind, val description: String?, val mounted: Boolean, val readOnly: Boolean = false)
