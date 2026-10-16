package com.qtekfun.ultimatecamera.core.storage

import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Where the chosen folder's tree URI is kept on disk. */
interface RecordingLocationStore {
    /** The stored tree URI (null: none chosen), then every change. */
    val treeUri: Flow<String?>

    suspend fun setTreeUri(uri: String?)
}

/**
 * The tree URI lives in core-settings (key `recording_tree_uri`, decision D27): one settings file, one backup
 * (REQ-BAK-002), one reset. core-storage owns the grant, validation and fallback logic on top of it.
 */
class SettingsRecordingLocationStore(private val settings: SettingsRepository) : RecordingLocationStore {
    override val treeUri: Flow<String?> = settings.settings.map { it.recordingTreeUri }.distinctUntilChanged()

    override suspend fun setTreeUri(uri: String?) = settings.setRecordingTreeUri(uri)
}

/**
 * The recording location (REQ-STO-001): what the user chose, its persisted SAF grant, and the check before recording.
 *
 * Folder flow for the UI: launch `ActivityResultContracts.OpenDocumentTree` (or [AndroidStorageBackend.openFolderIntent]),
 * pass the result to [choose], show [LocationStatus.Unavailable.problem]'s message if it failed. Call [check] when the
 * camera opens and again when volumes change ([AndroidStorageBackend.volumeChanges]).
 */
class RecordingLocationRepository(
    private val store: RecordingLocationStore,
    private val backend: StorageBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val validator = LocationValidator(backend)

    /** The chosen location ([RecordingLocation.Default] while none is chosen). */
    val location: Flow<RecordingLocation> = store.treeUri.map(RecordingLocation::fromStored).distinctUntilChanged()

    /**
     * Takes the grant on a folder returned by the picker, validates it and, if usable, stores it and releases the
     * grants on any other folder. If it is not usable nothing changes and its grant is released again.
     */
    suspend fun choose(treeUri: String): LocationStatus = withContext(ioDispatcher) {
        val folder = RecordingLocation.Folder(treeUri)
        val tree = folder.tree
        if (tree == null || !tree.isLocalStorage) return@withContext LocationStatus.Unavailable(LocationProblem.UNSUPPORTED_PROVIDER)
        if (!backend.takePersistedPermission(treeUri)) return@withContext LocationStatus.Unavailable(LocationProblem.PERMISSION_LOST)
        val status = validator.validate(folder)
        if (status is LocationStatus.Ready) {
            store.setTreeUri(treeUri)
            releaseGrantsExcept(treeUri)
        } else if (currentTreeUri() != treeUri) {
            backend.releasePersistedPermission(treeUri)
        }
        status
    }

    /** Goes back to [RecordingLocation.Default] and releases every folder grant. */
    suspend fun useDefault() = withContext(ioDispatcher) {
        store.setTreeUri(null)
        releaseGrantsExcept(null)
    }

    /** Validates the chosen location and picks where the next recording goes (see [LocationCheck]). */
    suspend fun check(): LocationCheck = withContext(ioDispatcher) {
        val chosen = RecordingLocation.fromStored(currentTreeUri())
        val status = validator.validate(chosen)
        when {
            status is LocationStatus.Ready -> LocationCheck(chosen, status, chosen, status)
            chosen == RecordingLocation.Default -> LocationCheck(chosen, status, null, status)
            else -> {
                val fallback = validator.validate(RecordingLocation.Default)
                LocationCheck(chosen, status, RecordingLocation.Default.takeIf { fallback is LocationStatus.Ready }, fallback)
            }
        }
    }

    private suspend fun currentTreeUri(): String? = store.treeUri.first()

    private fun releaseGrantsExcept(keep: String?) {
        backend.persistedTreeUris().filter { it != keep }.forEach(backend::releasePersistedPermission)
    }
}
