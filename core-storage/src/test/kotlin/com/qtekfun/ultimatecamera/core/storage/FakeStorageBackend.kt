package com.qtekfun.ultimatecamera.core.storage

import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream

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

    /** MediaStore names of the connected drives, as [removableVolumeNames] reports them. */
    val removable = mutableListOf<String>()

    override fun removableVolumeNames(): List<String> = removable.toList()

    override fun volume(volumeId: String) = volumes[volumeId]

    override fun folderExists(location: RecordingLocation) = location in existingFolders

    override fun freeBytes(location: RecordingLocation): Long? = if (location in free) free[location] else 64L * 1024 * 1024 * 1024

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

    override fun openRead(location: RecordingLocation, displayName: String): InputStream? =
        files[location]?.get(displayName)?.let { ByteArrayInputStream(ByteArray(it.toInt())) }

    override fun deleteIfExists(location: RecordingLocation, displayName: String) {
        files[location]?.remove(displayName)
    }

    /** A drive MediaStore can record to, with [freeBytes] free. */
    fun addDrive(volumeName: String, freeBytes: Long?, mounted: Boolean = true): RecordingLocation.Volume {
        volumes[volumeName] = VolumeInfo(volumeName, VolumeKind.REMOVABLE, "USB drive $volumeName", mounted)
        removable += volumeName
        return RecordingLocation.Volume(volumeName).also {
            existingFolders += it
            free[it] = freeBytes
        }
    }

    /** The drive is pulled out: gone from the volume list and from MediaStore. */
    fun removeDrive(volumeName: String) {
        volumes.remove(volumeName)
        removable -= volumeName
    }

    fun addUsbFolder(treeUri: String, volumeId: String, mounted: Boolean = true): RecordingLocation.Folder {
        volumes[volumeId] = VolumeInfo(volumeId, VolumeKind.REMOVABLE, "USB drive", mounted)
        return RecordingLocation.Folder(treeUri).also {
            existingFolders += it
            offeredGrants += treeUri
        }
    }
}

class FakeLocationStore(initial: String? = null, initialMode: String? = null) : RecordingLocationStore {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    private val modeState = kotlinx.coroutines.flow.MutableStateFlow(initialMode)
    override val treeUri = state
    override val mode = modeState

    override suspend fun setTreeUri(uri: String?) {
        state.value = uri
    }

    override suspend fun setMode(mode: String?) {
        modeState.value = mode
    }
}
