package com.qtekfun.ultimatecamera.core.storage

import java.io.FileDescriptor
import java.io.IOException

/** In-memory [StorageBackend]: one folder per location, files are names plus a byte count. */
class FakeStorageBackend : StorageBackend {
    val grants = mutableSetOf<String>()

    /** Grants the picker offered; [takePersistedPermission] fails for any other URI. */
    val offeredGrants = mutableSetOf<String>()
    val volumes = mutableMapOf(SafTree.PRIMARY_VOLUME_ID to VolumeInfo(SafTree.PRIMARY_VOLUME_ID, VolumeKind.INTERNAL, "Internal shared storage", true))
    val existingFolders = mutableSetOf<RecordingLocation>(RecordingLocation.Default)
    val files = mutableMapOf<RecordingLocation, MutableMap<String, Long>>()
    val free = mutableMapOf<RecordingLocation, Long?>()
    var failCreate = false
    var failWriteAfterBytes: Long? = null
    var created = 0

    override fun hasPersistedPermission(treeUri: String) = treeUri in grants

    override fun takePersistedPermission(treeUri: String): Boolean = (treeUri in offeredGrants).also { if (it) grants += treeUri }

    override fun releasePersistedPermission(treeUri: String) {
        grants -= treeUri
    }

    override fun persistedTreeUris() = grants.toList()

    override fun volume(volumeId: String) = volumes[volumeId]

    override fun folderExists(location: RecordingLocation) = location in existingFolders

    override fun freeBytes(location: RecordingLocation) = free[location] ?: 64L * 1024 * 1024 * 1024

    override fun listFileNames(location: RecordingLocation): Set<String> = files[location]?.keys?.toSet().orEmpty()

    override fun createFile(location: RecordingLocation, displayName: String, mimeType: String, temporary: Boolean): OutputFile {
        if (failCreate || location !in existingFolders) throw IOException("cannot create $displayName")
        created++
        val folder = files.getOrPut(location) { mutableMapOf() }
        folder[displayName] = 0
        return object : OutputFile {
            override val uri = "fake://$displayName"
            override val fileDescriptor = FileDescriptor()
            var syncs = 0

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                val size = folder.getValue(displayName) + length
                failWriteAfterBytes?.let { if (size > it) throw IOException("disk removed") }
                folder[displayName] = size
            }

            override fun sync() {
                syncs++
            }

            override fun close() = Unit

            override fun delete() {
                folder.remove(displayName)
            }
        }
    }

    override fun deleteIfExists(location: RecordingLocation, displayName: String) {
        files[location]?.remove(displayName)
    }

    fun addUsbFolder(treeUri: String, volumeId: String, mounted: Boolean = true): RecordingLocation.Folder {
        volumes[volumeId] = VolumeInfo(volumeId, VolumeKind.REMOVABLE, "USB drive", mounted)
        return RecordingLocation.Folder(treeUri).also {
            existingFolders += it
            offeredGrants += treeUri
        }
    }
}

class FakeLocationStore(initial: String? = null) : RecordingLocationStore {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val treeUri = state

    override suspend fun setTreeUri(uri: String?) {
        state.value = uri
    }
}
