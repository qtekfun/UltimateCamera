package com.qtekfun.ultimatecamera.core.backup

import com.qtekfun.ultimatecamera.core.capabilities.CapabilityBackup
import com.qtekfun.ultimatecamera.core.capabilities.CapabilityStore
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.luts.FileLutLibrary
import com.qtekfun.ultimatecamera.core.luts.LutBackupAccess
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutIndexCodec
import com.qtekfun.ultimatecamera.core.luts.LutLibraryException
import com.qtekfun.ultimatecamera.core.luts.StagedLut
import com.qtekfun.ultimatecamera.core.settings.DataStoreSettingsRepository
import com.qtekfun.ultimatecamera.core.settings.SettingValue
import com.qtekfun.ultimatecamera.core.settings.SettingsBackupAccess
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException

/** Passes bytes through and keeps the last [EOCD_BYTES], whoever reads them (ZipInputStream reads ahead). */
private class TailStream(input: InputStream) : java.io.FilterInputStream(input) {
    private val last = ByteArray(EOCD_BYTES)
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) keep(byteArrayOf(it.toByte()), 0, 1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) keep(b, off, it) }

    private fun keep(b: ByteArray, off: Int, n: Int) {
        for (i in off until off + n) {
            last[(count % EOCD_BYTES).toInt()] = b[i]
            count++
        }
    }

    /** The stream ended with an end-of-central-directory record without a comment (as [BackupWriter] writes it). */
    fun endsWithEndOfCentralDirectory(): Boolean {
        if (count < EOCD_BYTES) return false
        val start = (count % EOCD_BYTES).toInt()
        val tail = ByteArray(EOCD_BYTES) { last[(start + it) % EOCD_BYTES] }
        return tail.copyOf(EOCD_SIGNATURE.size).contentEquals(EOCD_SIGNATURE) && tail[EOCD_BYTES - 2] == 0.toByte() && tail[EOCD_BYTES - 1] == 0.toByte()
    }

    private companion object {
        const val EOCD_BYTES = 22
        val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    }
}

/** What an import would bring, shown before anything changes. */
data class BackupSummary(
    val manifest: BackupManifest,
    val encrypted: Boolean,
    val settingsCount: Int,
    /** Keys in the backup that stay as they are on this phone (SAF folder, audio input…). */
    val deviceSpecificKeys: List<String>,
    val lutCount: Int,
    val lutFolderCount: Int,
    val hasCapabilities: Boolean,
    /** The capability results were measured on this very phone and build, so they would be restored. */
    val capabilitiesApply: Boolean
)

/** Where an import goes. [capabilities] null leaves the stored catalogue alone. */
class RestoreTargets(val settings: SettingsBackupAccess, val luts: LutBackupAccess, val capabilities: CapabilityStore?, val identity: DeviceIdentity)

data class RestoreReport(val settingsRestored: Int, val skippedKeys: Set<String>, val lutCount: Int, val capabilities: CapabilityBackup.Outcome?)

/**
 * Reads a backup written by [BackupWriter]. [inspect] and [restore] each stream the whole file once and check every
 * entry against the manifest's sizes and SHA-256 (and, when encrypted, every chunk's tag), so a truncated or damaged
 * file is refused. [restore] changes nothing until the whole file has been read and staged on disk under its staging
 * directory.
 */
class BackupReader(private val maxLutBytes: Long = FileLutLibrary.DEFAULT_MAX_FILE_BYTES) {
    /** Validates the backup and describes it. [open] gives a fresh stream of the file; [current] is this phone. */
    fun inspect(open: () -> InputStream, password: CharArray?, current: DeviceIdentity?): BackupSummary {
        val pass = Pass(stage = null)
        open().use { read(it, password, pass) }
        val settings = pass.settings!!
        return BackupSummary(
            manifest = pass.manifest!!,
            encrypted = pass.encrypted,
            settingsCount = settings.size,
            deviceSpecificKeys = settings.keys.filter { it in SettingsBackupAccess.DEVICE_SPECIFIC_KEYS }.sorted(),
            lutCount = pass.luts.size,
            lutFolderCount = pass.folders.size,
            hasCapabilities = pass.capabilitiesSeen,
            capabilitiesApply = pass.capabilitiesSeen && current != null &&
                pass.manifest!!.device.fingerprint.isNotEmpty() && pass.manifest!!.device.fingerprint == current.fingerprint
        )
    }

    /**
     * Imports the backup: LUTs first (their ids may change on a merge, and the looks in the settings follow them), then
     * the settings, then the capability results when they were measured on this phone. With [replace] the library and the
     * settings become the backup's; otherwise the backup is merged in. [staging] is emptied and deleted afterwards.
     */
    suspend fun restore(open: () -> InputStream, password: CharArray?, replace: Boolean, staging: File, targets: RestoreTargets): RestoreReport {
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create $staging")
        try {
            val pass = Pass(stage = staging)
            open().use { read(it, password, pass) }
            val staged = pass.luts.map { StagedLut(it, pass.lutFiles.getValue(BackupPaths.lutFile(it))) }
            val ids = targets.luts.restore(pass.folders, staged, replace)
            val values = pass.settings!!.mapValues { (key, value) -> remapLook(key, value, ids) }
            val skipped = targets.settings.restoreValues(values, replace)
            val capabilities = pass.capabilitiesFile?.let { file ->
                targets.capabilities?.let { CapabilityBackup.restore(file, it, targets.identity) }
            }
            return RestoreReport(values.size - skipped.size, skipped, staged.size, capabilities)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** State of one read: what was found, and (with [stage]) where entries were staged. */
    private class Pass(val stage: File?) {
        var encrypted = false
        var manifest: BackupManifest? = null
        var settings: Map<String, SettingValue>? = null
        var folders: List<String> = emptyList()
        var luts: List<LutEntry> = emptyList()
        val lutFiles = mutableMapOf<String, File>()
        var capabilitiesSeen = false
        var capabilitiesFile: File? = null
    }

    private fun read(raw: InputStream, password: CharArray?, pass: Pass) {
        val input = BufferedInputStream(raw)
        input.mark(MAGIC_BYTES)
        val magic = ByteArray(MAGIC_BYTES).also { it.fill(0) }
        input.read(magic)
        input.reset()
        val plain = when {
            magic.contentEquals(BackupCrypto.MAGIC) -> {
                pass.encrypted = true
                if (password == null || password.isEmpty()) fail(BackupException.Reason.PASSWORD_REQUIRED, "encrypted backup")
                BackupCrypto.decrypt(input, password)
            }
            magic.contentEquals(ZIP_MAGIC) -> input
            else -> fail(BackupException.Reason.NOT_A_BACKUP, "not a backup")
        }
        val tail = TailStream(plain)
        try {
            readZip(tail, pass)
            // Read to the end: with encryption this checks the last chunk's tag (truncation, appended data); either way
            // the zip must end with its end-of-central-directory record, so a cut-off file is refused.
            tail.copyTo(OutputStream.nullOutputStream(), BUFFER)
            if (!tail.endsWithEndOfCentralDirectory()) fail(BackupException.Reason.CORRUPT, "the zip is cut short")
        } catch (e: BackupException) {
            throw e
        } catch (e: SerializationException) {
            fail(BackupException.Reason.CORRUPT, "unreadable JSON", e)
        } catch (e: IllegalArgumentException) {
            fail(BackupException.Reason.CORRUPT, "unreadable JSON", e)
        } catch (e: LutLibraryException) {
            val reason = if (e.reason == LutLibraryException.Reason.INDEX_TOO_NEW) BackupException.Reason.TOO_NEW else BackupException.Reason.CORRUPT
            fail(reason, e.message ?: "bad LUT index", e)
        } catch (e: IOException) {
            // ZipException, EOFException: a truncated or damaged zip.
            fail(BackupException.Reason.CORRUPT, e.message ?: "damaged zip", e)
        }
    }

    private fun readZip(plain: InputStream, pass: Pass) {
        val zip = ZipInputStream(plain)
        val first = zip.nextEntry
        if (first?.name != BackupPaths.MANIFEST) fail(BackupException.Reason.NOT_A_BACKUP, "no manifest")
        val manifest = BackupJson.json.decodeFromString(BackupManifest.serializer(), String(readSmall(zip, MAX_JSON_BYTES)))
        checkManifest(manifest)
        pass.manifest = manifest
        val expected = manifest.entries.associateBy { it.name }
        val seen = mutableSetOf<String>()
        while (true) {
            val entry = zip.nextEntry ?: break
            val promised = expected[entry.name] ?: fail(BackupException.Reason.CORRUPT, "unexpected entry ${entry.name}")
            if (!seen.add(entry.name)) fail(BackupException.Reason.CORRUPT, "duplicate entry ${entry.name}")
            readEntry(zip, promised, pass)
        }
        val missing = expected.keys - seen
        if (missing.isNotEmpty()) fail(BackupException.Reason.CORRUPT, "missing ${missing.first()}")
        if (pass.settings == null) fail(BackupException.Reason.CORRUPT, "no settings")
        val names = pass.luts.map(BackupPaths::lutFile).toSet()
        if (names.size != pass.luts.size || names != seen.filter { it.startsWith(BackupPaths.LUT_FILES) }.toSet()) {
            fail(BackupException.Reason.CORRUPT, "LUT index and files differ")
        }
    }

    private fun checkManifest(manifest: BackupManifest) {
        if (manifest.format != BackupManifest.FORMAT) fail(BackupException.Reason.NOT_A_BACKUP, "format ${manifest.format}")
        val tooNew = manifest.formatVersion > BackupManifest.FORMAT_VERSION ||
            manifest.schemas.settings > DataStoreSettingsRepository.SCHEMA_VERSION ||
            manifest.schemas.lutIndex > LutIndexCodec.SCHEMA_VERSION
        if (tooNew) fail(BackupException.Reason.TOO_NEW, "written by a newer version (${manifest.appVersion})")
        if (manifest.entries.any { it.bytes < 0 || it.name.contains("..") }) fail(BackupException.Reason.CORRUPT, "bad entry list")
    }

    private fun readEntry(zip: ZipInputStream, promised: BackupEntry, pass: Pass) {
        val name = promised.name
        when {
            name == BackupPaths.SETTINGS -> {
                val bytes = checked(zip, promised, MAX_JSON_BYTES)
                val file = BackupJson.json.decodeFromString(SettingsFile.serializer(), String(bytes))
                if (file.schemaVersion > DataStoreSettingsRepository.SCHEMA_VERSION) fail(BackupException.Reason.TOO_NEW, "settings ${file.schemaVersion}")
                pass.settings = file.values.mapNotNull { (key, typed) -> BackupJson.decode(typed)?.let { key to it } }.toMap()
            }
            name == BackupPaths.LUT_INDEX -> {
                val (folders, luts) = LutIndexCodec.decode(String(checked(zip, promised, MAX_INDEX_BYTES)))
                pass.folders = folders
                pass.luts = luts
            }
            name.startsWith(BackupPaths.LUT_FILES) -> {
                val id = name.removePrefix(BackupPaths.LUT_FILES).substringBeforeLast('.')
                if (!LutIndexCodec.isSafeId(id)) fail(BackupException.Reason.CORRUPT, "bad LUT name $name")
                val target = pass.stage?.let { File(it, "lut-${name.removePrefix(BackupPaths.LUT_FILES)}") }
                stream(zip, promised, maxLutBytes, target)
                target?.let { pass.lutFiles[name] = it }
            }
            name == BackupPaths.CAPABILITIES -> {
                val target = pass.stage?.let { File(it, CapabilityStore.FILE_NAME) }
                stream(zip, promised, MAX_INDEX_BYTES.toLong(), target)
                pass.capabilitiesSeen = true
                // A catalogue of another schema is a stale cache: skipped, not an error.
                pass.capabilitiesFile = target?.takeIf { pass.manifest?.schemas?.capabilities == DeviceCapabilities.SCHEMA_VERSION }
            }
            else -> fail(BackupException.Reason.CORRUPT, "unexpected entry $name")
        }
    }

    private fun checked(zip: InputStream, promised: BackupEntry, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        stream(zip, promised, max.toLong(), out)
        return out.toByteArray()
    }

    private fun stream(zip: InputStream, promised: BackupEntry, max: Long, target: File?) {
        if (target == null) stream(zip, promised, max, OutputStream.nullOutputStream()) else target.outputStream().use { stream(zip, promised, max, it) }
    }

    /** Copies one entry to [out] in pieces, failing past its promised size or [max], then checks size and SHA-256. */
    private fun stream(zip: InputStream, promised: BackupEntry, max: Long, out: OutputStream) {
        if (promised.bytes > max) fail(BackupException.Reason.CORRUPT, "${promised.name} is too large")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > promised.bytes) fail(BackupException.Reason.CORRUPT, "${promised.name} is longer than promised")
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
        }
        if (total != promised.bytes || digest.digest().toHex() != promised.sha256) {
            fail(BackupException.Reason.CORRUPT, "${promised.name} does not match the manifest")
        }
    }

    private fun readSmall(zip: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) fail(BackupException.Reason.CORRUPT, "manifest too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        fun fail(reason: BackupException.Reason, message: String, cause: Throwable? = null): Nothing = throw BackupException(reason, message, cause)

        const val MAGIC_BYTES = 4
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val BUFFER = 64 * 1024
        const val MAX_JSON_BYTES = 1024 * 1024
        const val MAX_INDEX_BYTES = 8 * 1024 * 1024

        /** The look tokens of feature-camera's `LutChoice` (`lib:<id>`) follow the LUT ids of the restored library. */
        val LOOK_KEYS = setOf("recording_look", "monitor_look")
        const val LIBRARY_PREFIX = "lib:"

        fun remapLook(key: String, value: SettingValue, ids: Map<String, String>): SettingValue {
            if (key !in LOOK_KEYS || value !is SettingValue.Text || !value.value.startsWith(LIBRARY_PREFIX)) return value
            val id = value.value.removePrefix(LIBRARY_PREFIX)
            return ids[id]?.let { SettingValue.Text(LIBRARY_PREFIX + it) } ?: value
        }
    }
}
