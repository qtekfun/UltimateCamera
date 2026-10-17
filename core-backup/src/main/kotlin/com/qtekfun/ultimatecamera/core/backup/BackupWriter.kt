package com.qtekfun.ultimatecamera.core.backup

import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.luts.LutBackupAccess
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutIndexCodec
import com.qtekfun.ultimatecamera.core.settings.SettingsBackupAccess
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Where a backup's content comes from. [capabilities] is the catalogue file, or null to leave the results out. */
class BackupSources(val settings: SettingsBackupAccess, val luts: LutBackupAccess, val capabilities: File? = null)

/** Who and what wrote the backup, for the manifest. */
data class BackupOrigin(val appVersion: String, val appVersionCode: Long, val device: BackupDevice, val createdAt: String)

/** Zip entry names (D65). */
internal object BackupPaths {
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val LUT_INDEX = "luts/index.json"
    const val LUT_FILES = "luts/files/"
    const val CAPABILITIES = "capabilities/device-capabilities.json"

    fun lutFile(entry: LutEntry) = "$LUT_FILES${entry.id}.${entry.lutFormat.extension}"
}

/**
 * Writes a backup (REQ-BAK-001/002) to a stream, normally a SAF document. Everything streams: LUT files go from disk
 * to the zip (and through the cipher) in 64 KiB pieces, so a 60 MB LUT never sits in memory. Pair keys are never
 * included (D39): they live in their own Keystore-wrapped file that the backup does not read.
 */
object BackupWriter {
    private const val BUFFER = 64 * 1024

    /** Writes the backup and closes [out]. With a [password] the zip is wrapped by [BackupCrypto]. */
    suspend fun write(out: OutputStream, sources: BackupSources, origin: BackupOrigin, password: CharArray? = null) {
        val settingsBytes = BackupJson.json.encodeToString(
            SettingsFile.serializer(),
            SettingsFile(sources.settings.schemaVersion, sources.settings.exportValues().mapValues { BackupJson.encode(it.value) })
        ).toByteArray()
        val view = sources.luts.backupView()
        val indexBytes = view.indexJson().toByteArray()
        val capabilities = sources.capabilities?.takeIf { it.isFile }
        val entries = buildList {
            add(BackupEntry(BackupPaths.SETTINGS, settingsBytes.size.toLong(), sha256(settingsBytes.inputStream())))
            add(BackupEntry(BackupPaths.LUT_INDEX, indexBytes.size.toLong(), sha256(indexBytes.inputStream())))
            view.luts.forEach { add(BackupEntry(BackupPaths.lutFile(it), it.bytes, it.sha256)) }
            capabilities?.let { add(BackupEntry(BackupPaths.CAPABILITIES, it.length(), it.inputStream().use(::sha256))) }
        }
        val manifest = BackupManifest(
            appVersion = origin.appVersion,
            appVersionCode = origin.appVersionCode,
            createdAt = origin.createdAt,
            device = origin.device,
            schemas = BackupSchemas(
                settings = sources.settings.schemaVersion,
                lutIndex = LutIndexCodec.SCHEMA_VERSION,
                capabilities = capabilities?.let { DeviceCapabilities.SCHEMA_VERSION }
            ),
            entries = entries
        )
        val sink = if (password != null) BackupCrypto.encrypt(out, password) else out
        ZipOutputStream(sink).use { zip ->
            zip.put(BackupPaths.MANIFEST, BackupJson.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray().inputStream())
            zip.put(BackupPaths.SETTINGS, settingsBytes.inputStream())
            zip.put(BackupPaths.LUT_INDEX, indexBytes.inputStream())
            view.luts.forEach { lut ->
                val sha = view.open(lut).use { zip.put(BackupPaths.lutFile(lut), it) }
                if (sha != lut.sha256) throw BackupException(BackupException.Reason.CORRUPT, "LUT ${lut.id} does not match its checksum")
            }
            capabilities?.let { file -> file.inputStream().use { zip.put(BackupPaths.CAPABILITIES, it) } }
        }
    }

    /** Streams [input] into a new entry; returns its SHA-256. */
    private fun ZipOutputStream.put(name: String, input: InputStream): String {
        putNextEntry(ZipEntry(name))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        val hashed = DigestInputStream(input, digest)
        while (true) {
            val n = hashed.read(buffer)
            if (n < 0) break
            write(buffer, 0, n)
        }
        closeEntry()
        return digest.digest().toHex()
    }

    internal fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
