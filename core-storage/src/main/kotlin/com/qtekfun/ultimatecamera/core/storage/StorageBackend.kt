package com.qtekfun.ultimatecamera.core.storage

import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException

/**
 * Platform access the storage logic needs: SAF grants, volumes, files. [AndroidStorageBackend] is the real one; tests use
 * a fake. Methods may block on I/O: call them off the main thread. Failures are reported as [IOException].
 */
interface StorageBackend : Volumes {
    /** True when the app holds a persisted read and write grant on [treeUri]. */
    fun hasPersistedPermission(treeUri: String): Boolean

    /** Takes the persisted read/write grant the folder picker offered; false if the system refused it. */
    fun takePersistedPermission(treeUri: String): Boolean

    fun releasePersistedPermission(treeUri: String)

    /** Every tree URI the app holds a persisted grant on (Android caps them, so stale ones are released). */
    fun persistedTreeUris(): List<String>

    /** True when [location]'s folder exists and is a directory. */
    fun folderExists(location: RecordingLocation): Boolean

    /** Free bytes on [location]'s volume, or null if unknown. */
    fun freeBytes(location: RecordingLocation): Long?

    /** Display names of the files directly in [location] (for the default: its video and audio folders). */
    @Throws(IOException::class)
    fun listFileNames(location: RecordingLocation): Set<String>

    /**
     * Creates and opens [displayName] for writing in [location]. [temporary] files (probes, the speed test) are hidden
     * from galleries where the platform allows it, and the caller deletes them.
     */
    @Throws(IOException::class)
    fun createFile(location: RecordingLocation, displayName: String, mimeType: String, temporary: Boolean = false): OutputFile

    /** Deletes [displayName] from [location] if it exists (e.g. a speed-test file left behind by a crash). */
    @Throws(IOException::class)
    fun deleteIfExists(location: RecordingLocation, displayName: String)
}

/** The storage volumes the system reports (part of [StorageBackend]). */
interface Volumes {
    /**
     * MediaStore names of the removable volumes (USB drives, SD cards) mounted as public storage now, for
     * [RecordingLocation.Volume]. The phone's own storage is not included.
     */
    fun removableVolumeNames(): List<String>

    /** The volume with this id ([SafTree.PRIMARY_VOLUME_ID], a UUID or a MediaStore volume name), or null if absent. */
    fun volume(volumeId: String): VolumeInfo?
}

/** Where [WriteSpeedTest] streams bytes. */
interface WriteSink {
    @Throws(IOException::class)
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Flushes written data to the device (fsync). */
    @Throws(IOException::class)
    fun sync()
}

/** A file opened for writing by [StorageBackend.createFile]. [close] keeps it; [delete] closes and removes it. */
interface OutputFile :
    WriteSink,
    Closeable {
    /** The file's content URI. */
    val uri: String

    /** The open descriptor, valid until [close]: e.g. for `MediaMuxer(FileDescriptor, …)`. */
    val fileDescriptor: FileDescriptor

    @Throws(IOException::class)
    fun delete()
}
