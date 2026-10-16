package com.qtekfun.ultimatecamera.core.net.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A phone this one has paired with. [key] is the long-term pair key (32 bytes, base64url), secret. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val role: DeviceRole,
    val key: String,
    val endpoints: List<Endpoint> = emptyList(),
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long? = null
) {
    init {
        require(Secrets.isValidDeviceId(id)) { "bad device id" }
        require(B64.decodeOrNull(key)?.size == Secrets.KEY_BYTES) { "bad key" }
    }

    fun keyBytes(): ByteArray = requireNotNull(B64.decodeOrNull(key))

    override fun toString(): String = "PairedDevice($id, $name, $role, key=<hidden>, $endpoints)"
}

/** On-disk document. [schema] is bumped on any incompatible change; older files are migrated on load. */
@Serializable
data class PairedDevicesFile(
    val schema: Int = PairedDeviceStore.SCHEMA_VERSION,
    @SerialName("own_id") val ownId: String,
    val devices: List<PairedDevice> = emptyList()
)

/** What [PairedDeviceStore.load] found. */
sealed interface PairedDevicesLoad {
    val file: PairedDevicesFile

    /** Read normally (or the file did not exist yet: a new own id was generated). */
    data class Ok(override val file: PairedDevicesFile, val created: Boolean) : PairedDevicesLoad

    /** Unreadable; it was renamed to `*.corrupt` and an empty list is used (pairings must be redone). */
    data class Recovered(override val file: PairedDevicesFile, val reason: String) : PairedDevicesLoad

    /** Written by a newer app version: used read-only, never overwritten, so a downgrade cannot destroy it. */
    data class NewerSchema(override val file: PairedDevicesFile, val schema: Int) : PairedDevicesLoad
}

/**
 * Paired devices as a small versioned JSON file in app-private storage (part of the backup, REQ-BAK-002). Writes are
 * atomic: a temp file in the same directory is written, fsynced and renamed over the old one, so a crash leaves either
 * the old or the new list, never half a file. Not thread-safe by itself: callers serialise access (one repository).
 */
class PairedDeviceStore(private val file: File) {
    private var readOnly = false

    fun load(): PairedDevicesLoad {
        if (!file.exists()) {
            readOnly = false
            return PairedDevicesLoad.Ok(PairedDevicesFile(ownId = Secrets.newDeviceId()), created = true)
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return recover("read failed: ${e.message}")
        }
        return try {
            val schema = json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.int ?: 0
            if (schema > SCHEMA_VERSION) {
                readOnly = true
                PairedDevicesLoad.NewerSchema(json.decodeFromString(PairedDevicesFile.serializer(), text), schema)
            } else {
                readOnly = false
                PairedDevicesLoad.Ok(json.decodeFromString(PairedDevicesFile.serializer(), text).copy(schema = SCHEMA_VERSION), created = false)
            }
        } catch (e: SerializationException) {
            recover("bad json: ${e.message}")
        } catch (e: IllegalArgumentException) {
            recover("bad content: ${e.message}")
        }
    }

    /** Writes [content] atomically. Throws [IOException] when the disk refuses, [IllegalStateException] after a newer schema. */
    fun save(content: PairedDevicesFile) {
        check(!readOnly) { "paired devices were written by a newer app version; not overwriting" }
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(PairedDevicesFile.serializer(), content.copy(schema = SCHEMA_VERSION)).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun recover(reason: String): PairedDevicesLoad {
        readOnly = false
        runCatching { file.renameTo(File(file.path + ".corrupt")) }
        return PairedDevicesLoad.Recovered(PairedDevicesFile(ownId = Secrets.newDeviceId()), reason)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "paired-devices.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }
    }
}

/** Pure edits of a [PairedDevicesFile]; the caller saves the result. */
object PairedDevices {
    /** Adds or replaces (same id) a device; a re-pairing gets the new key. */
    fun upsert(file: PairedDevicesFile, device: PairedDevice): PairedDevicesFile = file.copy(devices = file.devices.filterNot { it.id == device.id } + device)

    fun remove(file: PairedDevicesFile, id: String): PairedDevicesFile = file.copy(devices = file.devices.filterNot { it.id == id })

    /** Records fresh endpoints (from discovery or a connection) and the time a device was last reached. */
    fun touch(file: PairedDevicesFile, id: String, endpoints: List<Endpoint>?, nowEpochMs: Long): PairedDevicesFile = file.copy(
        devices = file.devices.map {
            if (it.id != id) it else it.copy(endpoints = endpoints?.take(PairingPayload.MAX_ENDPOINTS) ?: it.endpoints, lastSeenEpochMs = nowEpochMs)
        }
    )
}
