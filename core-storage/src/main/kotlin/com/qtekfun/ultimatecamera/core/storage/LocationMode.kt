package com.qtekfun.ultimatecamera.core.storage

/**
 * What the user asked for as the recording location (decision D50), stored as text in core-settings
 * (`recording_location_mode`). The concrete [RecordingLocation] is resolved by [LocationPolicy] before each take.
 */
sealed interface LocationMode {
    /** The default: the connected drive with the most free space, else the phone. */
    data object Automatic : LocationMode

    /** Always the phone's shared storage ([RecordingLocation.Default]). */
    data object Phone : LocationMode

    /** One drive by MediaStore volume name ([RecordingLocation.Volume]). */
    data class Volume(val volumeName: String) : LocationMode

    /** The folder picked with SAF (`recording_tree_uri`). */
    data object Folder : LocationMode

    /** The persisted text. */
    val stored: String
        get() = when (this) {
            Automatic -> AUTO
            Phone -> PHONE
            is Volume -> VOLUME_PREFIX + volumeName
            Folder -> FOLDER
        }

    companion object {
        private const val AUTO = "auto"
        private const val PHONE = "phone"
        private const val FOLDER = "folder"
        private const val VOLUME_PREFIX = "volume:"

        /**
         * Reads the stored text. Never chosen (null): a folder stored by an older version means [Folder], else
         * [Automatic]. Unknown text (a newer version) and `folder` without a folder are [Automatic].
         */
        fun parse(stored: String?, treeUri: String?): LocationMode {
            val hasFolder = !treeUri.isNullOrBlank()
            return when {
                stored.isNullOrBlank() -> if (hasFolder) Folder else Automatic
                stored == PHONE -> Phone
                stored == FOLDER -> if (hasFolder) Folder else Automatic
                stored.startsWith(VOLUME_PREFIX) && stored.length > VOLUME_PREFIX.length -> Volume(stored.substring(VOLUME_PREFIX.length))
                else -> Automatic
            }
        }
    }
}

/**
 * A connected drive MediaStore can record to: mounted, writable, removable.
 *
 * @property freeBytes null when the system does not tell (then it is never preferred over a drive that does).
 */
data class Drive(val location: RecordingLocation.Volume, val volume: VolumeInfo, val freeBytes: Long?)

/**
 * Everything a location picker shows (camera screen and Settings).
 *
 * @property automaticTarget where [LocationMode.Automatic] records now.
 * @property folder the SAF folder picked earlier (listed even while another mode is active), or null.
 * @property phoneFreeBytes free space on the phone's shared storage, null if unknown.
 */
data class LocationOptions(
    val mode: LocationMode,
    val automaticTarget: RecordingLocation,
    val drives: List<Drive>,
    val folder: RecordingLocation.Folder?,
    val phoneFreeBytes: Long? = null
)

/** Turns a [LocationMode] into the place the next take goes (decision D50). Pure. */
object LocationPolicy {
    /** The drive with the most free space, or the phone when none is connected. */
    fun automaticTarget(drives: List<Drive>): RecordingLocation =
        drives.maxWithOrNull(compareBy<Drive> { it.freeBytes ?: -1L }.thenByDescending { it.location.volumeName })?.location ?: RecordingLocation.Default

    /**
     * The location [mode] asks for. [lastAutomatic] is the drive Automatic used earlier in this session: when it has
     * gone and no other drive is connected, it is still returned so that validation fails and the take falls back to
     * the phone with the visible warning, instead of silently moving to the phone.
     */
    fun target(mode: LocationMode, treeUri: String?, drives: List<Drive>, lastAutomatic: RecordingLocation.Volume? = null): RecordingLocation = when (mode) {
        LocationMode.Automatic -> {
            val auto = automaticTarget(drives)
            if (auto == RecordingLocation.Default && lastAutomatic != null) lastAutomatic else auto
        }
        LocationMode.Phone -> RecordingLocation.Default
        is LocationMode.Volume -> RecordingLocation.Volume(mode.volumeName)
        LocationMode.Folder -> RecordingLocation.fromStored(treeUri)
    }

    /** The drives among [volumeNames] that can take a recording now. */
    fun drives(volumeNames: List<String>, volume: (String) -> VolumeInfo?, freeBytes: (RecordingLocation) -> Long?): List<Drive> =
        volumeNames.mapNotNull { name ->
            val info = volume(name)?.takeIf { it.mounted && !it.readOnly } ?: return@mapNotNull null
            val location = RecordingLocation.Volume(name)
            Drive(location, info, freeBytes(location))
        }
}
