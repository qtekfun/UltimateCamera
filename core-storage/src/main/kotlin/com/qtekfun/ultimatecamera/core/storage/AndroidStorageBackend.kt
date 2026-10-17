package com.qtekfun.ultimatecamera.core.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [StorageBackend] on SAF (`DocumentsContract`), `StorageManager` and MediaStore. No permission is needed: SAF grants
 * come from the user's pick, and MediaStore lets an app write its own media without one. Thin glue only: the decisions
 * live in [LocationValidator], [RecordingLocationRepository] and [WriteSpeedTest], which are unit-tested.
 */
class AndroidStorageBackend(context: Context) : StorageBackend {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val storageManager = appContext.getSystemService(StorageManager::class.java)

    override fun hasPersistedPermission(treeUri: String): Boolean = resolver.persistedUriPermissions.any {
        it.uri.toString() == treeUri && it.isReadPermission && it.isWritePermission
    }

    override fun takePersistedPermission(treeUri: String): Boolean = try {
        resolver.takePersistableUriPermission(Uri.parse(treeUri), READ_WRITE)
        true
    } catch (_: SecurityException) {
        false
    }

    override fun releasePersistedPermission(treeUri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(treeUri), READ_WRITE)
        } catch (_: SecurityException) {
            // Not held any more: nothing to release.
        }
    }

    override fun persistedTreeUris(): List<String> = resolver.persistedUriPermissions
        .filter { DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri.toString() }

    override fun volume(volumeId: String): VolumeInfo? = findVolume(volumeId)?.let { volume ->
        VolumeInfo(
            id = volumeId,
            kind = VolumeKind.classify(volume.isPrimary, volume.isEmulated, volume.isRemovable),
            description = volume.getDescription(appContext),
            mounted = StoragePlatformRules.mounted(volume.state),
            readOnly = StoragePlatformRules.readOnly(volume.state)
        )
    }

    override fun folderExists(location: RecordingLocation): Boolean = when (location) {
        RecordingLocation.Default, is RecordingLocation.Volume -> true // MediaStore creates the folders on the first insert.
        is RecordingLocation.Folder -> try {
            val tree = Uri.parse(location.treeUri)
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            } ?: false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun freeBytes(location: RecordingLocation): Long? = when (val source = StoragePlatformRules.freeSpaceSource(location)) {
        is FreeSpaceSource.DocumentsRoot -> rootAvailableBytes(source.volumeId) ?: statFs(source.volumeId)
        is FreeSpaceSource.Volume -> statFs(source.volumeName)
        FreeSpaceSource.Unknown -> null
    }

    override fun removableVolumeNames(): List<String> = StoragePlatformRules.removableVolumeNames(
        MediaStore.getExternalVolumeNames(appContext),
        setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaStore.VOLUME_EXTERNAL)
    ) { findVolume(it)?.isRemovable == true }

    private fun statFs(volumeId: String): Long? = findVolume(volumeId)?.directory?.let { directory ->
        try {
            StatFs(directory.path).availableBytes
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun listFileNames(location: RecordingLocation): Set<String> = io {
        when (location) {
            RecordingLocation.Default, is RecordingLocation.Volume -> mediaCollections(location).flatMapTo(HashSet()) { (collection, path) ->
                queryNames(collection, pendingArgs(StoragePlatformRules.SELECT_BY_FOLDER, arrayOf(path)))
            }
            is RecordingLocation.Folder -> {
                val tree = Uri.parse(location.treeUri)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { it.strings() }?.toSet() ?: throw IOException("cannot list ${location.treeUri}")
            }
        }
    }

    override fun createFile(location: RecordingLocation, displayName: String, mimeType: String, temporary: Boolean): OutputFile = io {
        when (location) {
            RecordingLocation.Default, is RecordingLocation.Volume -> {
                val (collection, path) = mediaCollectionFor(location, mimeType)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    // Pending rows are hidden from galleries and dropped by MediaStore if the app dies before deleting
                    // them. Recordings are not pending: a crash must never make MediaStore delete footage.
                    if (temporary) put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $displayName")
                open(uri) { resolver.delete(uri, null, null) }
            }
            is RecordingLocation.Folder -> {
                val tree = Uri.parse(location.treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val uri = DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
                    ?: throw IOException("cannot create $displayName in ${location.treeUri}")
                open(uri) { DocumentsContract.deleteDocument(resolver, uri) }
            }
        }
    }

    override fun openRead(location: RecordingLocation, displayName: String): InputStream? = io {
        val uri = when (location) {
            RecordingLocation.Default, is RecordingLocation.Volume -> mediaCollections(location).firstNotNullOfOrNull { (collection, path) ->
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    pendingArgs(StoragePlatformRules.SELECT_BY_NAME_IN_FOLDER, arrayOf(displayName, path)),
                    null
                )
                    ?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }
            }
            is RecordingLocation.Folder -> {
                val tree = Uri.parse(location.treeUri)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val rows = resolver.query(children, projection, null, null, null)?.use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                }.orEmpty()
                StoragePlatformRules.documentsNamed(rows, displayName).firstOrNull()?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            }
        }
        uri?.let { resolver.openInputStream(it) }
    }

    override fun deleteIfExists(location: RecordingLocation, displayName: String) {
        io {
            when (location) {
                RecordingLocation.Default, is RecordingLocation.Volume -> mediaCollections(location).forEach { (collection, path) ->
                    resolver.delete(collection, pendingArgs(StoragePlatformRules.SELECT_BY_NAME_IN_FOLDER, arrayOf(displayName, path)))
                }
                is RecordingLocation.Folder -> {
                    val tree = Uri.parse(location.treeUri)
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val rows = resolver.query(children, projection, null, null, null)?.use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                    }.orEmpty()
                    val matches = StoragePlatformRules.documentsNamed(rows, displayName)
                    matches.forEach { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
                }
            }
        }
    }

    /** The folder picker intent, for callers that do not use `ActivityResultContracts.OpenDocumentTree`. */
    fun openFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        READ_WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    )

    /** Emits whenever a volume is mounted, unmounted or changes state (e.g. a USB SSD is unplugged): re-run the check. */
    fun volumeChanges(): Flow<Unit> = callbackFlow {
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                trySend(Unit)
            }
        }
        storageManager.registerStorageVolumeCallback(appContext.mainExecutor, callback)
        awaitClose { storageManager.unregisterStorageVolumeCallback(callback) }
    }

    /** By SAF volume id (`primary` or the UUID) or MediaStore volume name (the UUID in lower case). */
    private fun findVolume(volumeId: String): StorageVolume? = if (volumeId == SafTree.PRIMARY_VOLUME_ID) {
        storageManager.primaryStorageVolume
    } else {
        storageManager.storageVolumes.firstOrNull { StoragePlatformRules.volumeMatches(volumeId, it.uuid, it.mediaStoreVolumeName) }
    }

    /** Free space as the documents provider reports it for the volume's root. */
    private fun rootAvailableBytes(volumeId: String): Long? = try {
        val roots = DocumentsContract.buildRootsUri(SafTree.EXTERNAL_STORAGE_AUTHORITY)
        val projection = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        resolver.query(roots, projection, null, null, null)?.use { cursor ->
            val rows = generateSequence { if (cursor.moveToNext()) cursor else null }
                .map { it.getString(0) to if (it.isNull(1)) null else it.getLong(1) }
            StoragePlatformRules.rootAvailableBytes(rows, volumeId)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun open(uri: Uri, remove: () -> Unit): OutputFile {
        val pfd = try {
            // Read-write: the take's post-process (D69) reads the closed MP4's boxes through the same descriptor.
            resolver.openFileDescriptor(uri, "rw")
        } catch (e: FileNotFoundException) {
            remove()
            throw e
        } ?: run {
            remove()
            throw IOException("cannot open $uri")
        }
        return DescriptorOutputFile(uri.toString(), pfd) { io(remove) }
    }

    private fun queryNames(collection: Uri, args: Bundle): List<String> =
        resolver.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), args, null)?.use { it.strings() } ?: emptyList()

    /** The MediaStore collections of the phone ([RecordingLocation.Default]) or of a drive ([RecordingLocation.Volume]). */
    private fun mediaCollections(location: RecordingLocation): List<Pair<Uri, String>> = MediaKind.entries.map { collection(location, it) }

    private fun mediaCollectionFor(location: RecordingLocation, mimeType: String): Pair<Uri, String> =
        collection(location, StoragePlatformRules.mediaKind(mimeType))

    private fun collection(location: RecordingLocation, kind: MediaKind): Pair<Uri, String> {
        val volume = StoragePlatformRules.mediaStoreVolume(location, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
        }
        return uri to kind.relativePath
    }

    private fun pendingArgs(selection: String, args: Array<String>) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }

    private fun Cursor.strings(): List<String> = buildList { while (moveToNext()) getString(0)?.let(::add) }

    private inline fun <T> io(block: () -> T): T = StoragePlatformRules.io(block)

    companion object {
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** One background-priority thread for storage tests, so they never compete with the camera, GL or encoder. */
        val lowPriorityDispatcher: CoroutineDispatcher by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                }, "uc-storage-io").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }
}

/** An [OutputFile] over a `ParcelFileDescriptor`; the stream owns and closes the descriptor. */
private class DescriptorOutputFile(override val uri: String, pfd: ParcelFileDescriptor, private val remove: () -> Unit) : OutputFile {
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    override val fileDescriptor: FileDescriptor = pfd.fileDescriptor

    override fun write(buffer: ByteArray, offset: Int, length: Int) = stream.write(buffer, offset, length)

    override fun sync() = stream.fd.sync()

    override fun close() = stream.close()

    override fun delete() {
        close()
        remove()
    }
}
