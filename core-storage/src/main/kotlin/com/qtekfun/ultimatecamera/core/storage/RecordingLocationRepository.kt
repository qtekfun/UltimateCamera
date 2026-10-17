package com.qtekfun.ultimatecamera.core.storage

import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Where the recording location choice is kept on disk. */
interface RecordingLocationStore {
    /** The stored tree URI (null: none chosen), then every change. */
    val treeUri: Flow<String?>

    /** The stored [LocationMode] text (null: never chosen), then every change. */
    val mode: Flow<String?>

    suspend fun setTreeUri(uri: String?)

    suspend fun setMode(mode: String?)
}

/**
 * The tree URI and the mode live in core-settings (keys `recording_tree_uri`, `recording_location_mode`; D27, D50):
 * one settings file, one backup (REQ-BAK-002), one reset. core-storage owns the grant, validation and fallback logic.
 */
class SettingsRecordingLocationStore(private val settings: SettingsRepository) : RecordingLocationStore {
    override val treeUri: Flow<String?> = settings.settings.map { it.recordingTreeUri }.distinctUntilChanged()
    override val mode: Flow<String?> = settings.settings.map { it.recordingLocationMode }.distinctUntilChanged()

    override suspend fun setTreeUri(uri: String?) = settings.setRecordingTreeUri(uri)

    override suspend fun setMode(mode: String?) = settings.setRecordingLocationMode(mode)
}

/**
 * The recording location (REQ-STO-001, D27, D50): what the user chose ([LocationMode]), the persisted SAF grant of a
 * picked folder, and the check before recording.
 *
 * Folder flow for the UI: launch `ActivityResultContracts.OpenDocumentTree` (or [AndroidStorageBackend.openFolderIntent]),
 * pass the result to [choose], show [LocationStatus.Unavailable.problem]'s message if it failed. Drives and the phone
 * are picked with [select]; [options] lists the choices. Call [check] before each take (never during one) and again when
 * volumes change ([AndroidStorageBackend.volumeChanges]).
 */
class RecordingLocationRepository(
    private val store: RecordingLocationStore,
    private val backend: StorageBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val validator = LocationValidator(backend)

    /** The drive Automatic recorded to in this session (see [LocationPolicy.target]). */
    @Volatile private var lastAutomatic: RecordingLocation.Volume? = null

    /** What the user asked for, then every change. */
    val mode: Flow<LocationMode> = combine(store.mode, store.treeUri, LocationMode::parse).distinctUntilChanged()

    /**
     * Takes the grant on a folder returned by the picker, validates it and, if usable, stores it, makes it the
     * location and releases the grants on any other folder. If it is not usable nothing changes and its grant is
     * released again.
     */
    suspend fun choose(treeUri: String): LocationStatus = withContext(ioDispatcher) {
        val folder = RecordingLocation.Folder(treeUri)
        val tree = folder.tree
        if (tree == null || !tree.isLocalStorage) return@withContext LocationStatus.Unavailable(LocationProblem.UNSUPPORTED_PROVIDER)
        if (!backend.takePersistedPermission(treeUri)) return@withContext LocationStatus.Unavailable(LocationProblem.PERMISSION_LOST)
        val status = validator.validate(folder)
        if (status is LocationStatus.Ready) {
            store.setTreeUri(treeUri)
            store.setMode(LocationMode.Folder.stored)
            lastAutomatic = null
            releaseGrantsExcept(treeUri)
        } else if (currentTreeUri() != treeUri) {
            backend.releasePersistedPermission(treeUri)
        }
        status
    }

    /**
     * Makes [mode] the location. The picked folder and its grant are kept, so it stays in the list; [LocationMode.Folder]
     * without a folder is ignored (use [choose]).
     */
    suspend fun select(mode: LocationMode) = withContext(ioDispatcher) {
        if (mode == LocationMode.Folder && currentTreeUri() == null) return@withContext
        store.setMode(mode.stored)
        lastAutomatic = null
    }

    /** Forgets the picked folder and releases every folder grant; if the folder was the location, Automatic takes over. */
    suspend fun forgetFolder() = withContext(ioDispatcher) {
        if (currentMode() == LocationMode.Folder) store.setMode(null)
        store.setTreeUri(null)
        releaseGrantsExcept(null)
    }

    /** The choices for a picker: mode, Automatic's current target, connected drives and the picked folder. Blocks on I/O. */
    suspend fun options(): LocationOptions = withContext(ioDispatcher) {
        val drives = drives()
        LocationOptions(
            mode = currentMode(),
            automaticTarget = LocationPolicy.automaticTarget(drives),
            drives = drives,
            folder = currentTreeUri()?.let(RecordingLocation::Folder),
            phoneFreeBytes = backend.freeBytes(RecordingLocation.Default)
        )
    }

    /** Resolves the mode, validates the result and picks where the next recording goes (see [LocationCheck]). */
    suspend fun check(): LocationCheck = withContext(ioDispatcher) {
        val mode = currentMode()
        val chosen = LocationPolicy.target(mode, currentTreeUri(), drives(), lastAutomatic)
        if (mode == LocationMode.Automatic) {
            // Remember the drive while it is there; once Automatic is back on the phone by itself, forget it.
            lastAutomatic = chosen as? RecordingLocation.Volume
        }
        val status = validator.validate(chosen)
        when {
            status is LocationStatus.Ready -> LocationCheck(chosen, status, chosen, status, mode)
            chosen == RecordingLocation.Default -> LocationCheck(chosen, status, null, status, mode)
            else -> {
                val fallback = validator.validate(RecordingLocation.Default)
                LocationCheck(chosen, status, RecordingLocation.Default.takeIf { fallback is LocationStatus.Ready }, fallback, mode)
            }
        }
    }

    private fun drives(): List<Drive> = LocationPolicy.drives(backend.removableVolumeNames(), backend::volume, backend::freeBytes)

    private suspend fun currentTreeUri(): String? = store.treeUri.first()?.takeIf { it.isNotBlank() }

    private suspend fun currentMode(): LocationMode = LocationMode.parse(store.mode.first(), store.treeUri.first())

    private fun releaseGrantsExcept(keep: String?) {
        backend.persistedTreeUris().filter { it != keep }.forEach(backend::releasePersistedPermission)
    }
}
