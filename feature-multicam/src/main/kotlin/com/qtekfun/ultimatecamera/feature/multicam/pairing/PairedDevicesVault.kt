package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.pairing.PairedDeviceStore
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesFile
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesLoad
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encrypts and decrypts small blobs with a key that never leaves the device (Android Keystore in the app). */
interface KeyProtector {
    /** Throws [GeneralSecurityException] (Keystore may also throw unchecked exceptions) when the key or the cipher is unavailable. */
    fun seal(plain: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when the blob was not sealed with this key or was altered. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The paired-devices document (core-net [PairedDevicesFile], with the pair keys) wrapped with a [KeyProtector] (D39,
 * D55): `paired-devices.sealed` holds `UCPK1` + the sealed JSON. Without a working protector (Keystore failure) it falls
 * back to the plain `paired-devices.json` of core-net's [PairedDeviceStore]. A plain file left by an earlier version is
 * migrated on load: sealed, then deleted. Writes are atomic (temp file, fsync, rename). Not thread-safe by itself.
 */
class PairedDevicesVault(private val dir: File, private val protector: KeyProtector?) {
    private val plainFile = File(dir, PairedDeviceStore.FILE_NAME)
    private val sealedFile = File(dir, SEALED_FILE_NAME)
    private val plainStore = PairedDeviceStore(plainFile)
    private var readOnly = false
    private var protectorWorks = protector != null

    /** True while the keys are stored sealed (false: plain fallback). */
    val protected: Boolean get() = protectorWorks

    fun load(): PairedDevicesLoad {
        readOnly = false
        if (sealedFile.exists()) return loadSealed()
        val plainExisted = plainFile.exists()
        val result = plainStore.load()
        if (result is PairedDevicesLoad.NewerSchema) {
            readOnly = true
            return result
        }
        if (plainExisted && protectorWorks && result is PairedDevicesLoad.Ok) migrate(result.file)
        return result
    }

    /** Throws [IOException] when the disk refuses, [IllegalStateException] after a newer schema. */
    @Suppress("TooGenericExceptionCaught") // Keystore failures are unchecked too (D77).
    fun save(content: PairedDevicesFile) {
        check(!readOnly) { "paired devices were written by a newer app version; not overwriting" }
        val sealed = protector?.takeIf { protectorWorks }?.let { p ->
            try {
                p.seal(json.encodeToString(PairedDevicesFile.serializer(), content.copy(schema = PairedDeviceStore.SCHEMA_VERSION)).toByteArray(Charsets.UTF_8))
            } catch (_: GeneralSecurityException) {
                protectorWorks = false
                null
            } catch (_: RuntimeException) {
                // Android Keystore also fails with unchecked ProviderException / IllegalStateException (D77): fall back
                // to the plain file instead of crashing the pairing that is saving its key.
                protectorWorks = false
                null
            }
        }
        if (sealed != null) {
            writeAtomically(sealedFile, MAGIC + sealed)
            plainFile.delete()
        } else {
            plainStore.save(content)
            sealedFile.delete()
        }
    }

    private fun migrate(content: PairedDevicesFile) {
        try {
            save(content)
        } catch (_: IOException) {
            // The plain file stays; the next load retries.
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Keystore failures are unchecked too (D77).
    private fun loadSealed(): PairedDevicesLoad {
        val p = protector ?: return recover("sealed file but no key protector")
        val bytes = try {
            sealedFile.readBytes()
        } catch (e: IOException) {
            return recover("read failed: ${e.message}")
        }
        if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return recover("bad header")
        val text = try {
            p.open(bytes.copyOfRange(MAGIC.size, bytes.size)).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            return recover("cannot unseal: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            // Unchecked Keystore failures (ProviderException...) must not crash the app at start (D77).
            return recover("cannot unseal: ${e.javaClass.simpleName}")
        }
        return try {
            val schema = json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.int ?: 0
            val file = json.decodeFromString(PairedDevicesFile.serializer(), text)
            if (plainFile.exists()) plainFile.delete() // leftover of an interrupted migration
            if (schema > PairedDeviceStore.SCHEMA_VERSION) {
                readOnly = true
                PairedDevicesLoad.NewerSchema(file, schema)
            } else {
                PairedDevicesLoad.Ok(file.copy(schema = PairedDeviceStore.SCHEMA_VERSION), created = false)
            }
        } catch (e: SerializationException) {
            recover("bad json: ${e.message}")
        } catch (e: IllegalArgumentException) {
            recover("bad content: ${e.message}")
        }
    }

    /** The sealed file is unusable (e.g. the Keystore key was wiped): set it aside; pairings must be redone. */
    private fun recover(reason: String): PairedDevicesLoad {
        runCatching { sealedFile.renameTo(File(sealedFile.path + ".corrupt")) }
        return PairedDevicesLoad.Recovered(PairedDevicesFile(ownId = Secrets.newDeviceId()), reason)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val SEALED_FILE_NAME = "paired-devices.sealed"
        private val MAGIC = "UCPK1".toByteArray(Charsets.US_ASCII)

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
