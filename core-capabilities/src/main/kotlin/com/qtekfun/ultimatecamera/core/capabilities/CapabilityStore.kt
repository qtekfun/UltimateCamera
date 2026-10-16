package com.qtekfun.ultimatecamera.core.capabilities

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What [CapabilityStore.load] found. Anything but [Loaded] means "probe again": the catalogue is a regenerable cache. */
sealed interface StoreLoad {
    data class Loaded(val capabilities: DeviceCapabilities) : StoreLoad
    data object Missing : StoreLoad

    /** Unreadable; the file was moved aside to [movedTo] (kept for diagnostics, replaced on the next corruption). */
    data class Corrupt(val error: String, val movedTo: File?) : StoreLoad

    /** Written by another schema version (older without a migration, or newer after a downgrade). */
    data class OtherSchema(val version: Int) : StoreLoad
}

/**
 * The capability catalogue on disk, one JSON file. Writes are atomic (a temporary file in the same directory, synced,
 * then renamed over the old one), so a crash or power loss leaves the old catalogue or the new one, never half of one.
 * Use [inNoBackupDir] in the app: the file must not be part of any backup (it describes one phone and one OS build).
 */
class CapabilityStore(val file: File) {
    private val temp get() = File(file.parentFile, "${file.name}.tmp")
    private val corrupt get() = File(file.parentFile, "${file.name}.corrupt")

    @Suppress("TooGenericExceptionCaught") // Any decoding failure of a cache file is "corrupt", never a crash.
    fun load(): StoreLoad {
        if (!file.isFile) return StoreLoad.Missing
        return try {
            val text = file.readText()
            val version = JSON.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int
                ?: throw SerializationException("no schemaVersion")
            if (version != DeviceCapabilities.SCHEMA_VERSION) return StoreLoad.OtherSchema(version)
            StoreLoad.Loaded(JSON.decodeFromString(DeviceCapabilities.serializer(), text))
        } catch (e: IOException) {
            moveAside(e)
        } catch (e: RuntimeException) {
            // SerializationException, IllegalArgumentException (bad enum or element type), NumberFormatException.
            moveAside(e)
        }
    }

    /** The stored catalogue if it is valid for [identity] with the current engine, ignoring completeness. */
    fun loadFor(identity: DeviceIdentity): DeviceCapabilities? = (load() as? StoreLoad.Loaded)?.capabilities
        ?.takeIf { it.reprobeReason(identity).let { reason -> reason == null || reason == ReprobeReason.INCOMPLETE } }

    fun save(capabilities: DeviceCapabilities) {
        file.parentFile?.mkdirs()
        val bytes = JSON.encodeToString(DeviceCapabilities.serializer(), capabilities).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /** Forgets the catalogue ("run auto-configuration again"). */
    fun clear() {
        file.delete()
        temp.delete()
    }

    private fun moveAside(e: Exception): StoreLoad {
        val moved = runCatching {
            Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
            corrupt
        }.getOrElse {
            file.delete()
            null
        }
        return StoreLoad.Corrupt("${e::class.simpleName}: ${e.message}", moved)
    }

    companion object {
        const val FILE_NAME = "device-capabilities.json"

        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** The store under [noBackupDir], i.e. `Context.getNoBackupFilesDir()` (excluded from Auto Backup by Android). */
        fun inNoBackupDir(noBackupDir: File): CapabilityStore = CapabilityStore(File(File(noBackupDir, "capabilities"), FILE_NAME))
    }
}
