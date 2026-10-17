package com.qtekfun.ultimatecamera.core.backup

import com.qtekfun.ultimatecamera.core.capabilities.CapabilityBackup
import com.qtekfun.ultimatecamera.core.capabilities.CapabilityStore
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.capabilities.StoreLoad
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.CubeLutWriter
import com.qtekfun.ultimatecamera.core.luts.BytesLutSource
import com.qtekfun.ultimatecamera.core.luts.FileLutLibrary
import com.qtekfun.ultimatecamera.core.settings.SettingValue
import com.qtekfun.ultimatecamera.core.settings.SettingsBackupAccess
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory [SettingsBackupAccess] with the real device-specific rules. */
internal class FakeSettings(initial: Map<String, SettingValue> = emptyMap(), override val schemaVersion: Int = 1) : SettingsBackupAccess {
    val values = initial.toMutableMap()

    override suspend fun exportValues(): Map<String, SettingValue> = values.toMap()

    override suspend fun restoreValues(values: Map<String, SettingValue>, replace: Boolean): Set<String> {
        val skipped = values.keys.filter { it in SettingsBackupAccess.DEVICE_SPECIFIC_KEYS }.toSet()
        if (replace) this.values.keys.retainAll(SettingsBackupAccess.DEVICE_SPECIFIC_KEYS)
        this.values += values - skipped
        return skipped
    }
}

class BackupRoundTripTest {
    // On disk under the module's build dir (the dev machine's /tmp is tmpfs).
    @get:Rule
    val tmp = TemporaryFolder(File("build/tmp/backup-test").apply { mkdirs() })

    private val pixel = DeviceIdentity("Google", "google", "Pixel 8", "shiba", 37, "BP1A", "google/shiba/shiba:17/BP1A/1:user/release-keys")
    private val origin = BackupOrigin("0.7.0", 70, BackupDevice("Google", "Pixel 8", pixel.fingerprint), "2026-10-17T10:00:00Z")
    private val password = "correct horse battery staple".toCharArray()

    private val warm = BytesLutSource("warm.cube", CubeLutWriter.write(CubeLut.generate(33, "Warm") { r, g, b -> Triple(r, g, b * 0.9f) }).toByteArray())

    /** Noise compresses badly, so the backup spans several cipher chunks. */
    private val noisy = BytesLutSource(
        "noisy.cube",
        CubeLutWriter.write(
            CubeLut.generate(33, "Noisy") { r, g, b ->
                fun n(x: Float) = (kotlin.math.sin(x * 43758.547f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                Triple(n(r + 2 * g + 3 * b), n(3 * r + g + 2 * b), n(2 * r + 3 * g + b + 0.5f))
            }
        ).toByteArray()
    )
    private val cool = BytesLutSource("cool.cube", CubeLutWriter.write(CubeLut.generate(5, "Cool") { r, g, b -> Triple(r * 0.9f, g, b) }).toByteArray())

    private val sourceSettings = FakeSettings(
        mapOf(
            "max_brightness" to SettingValue.Bool(true),
            "audio_gain_db" to SettingValue.FloatValue(6f),
            "zebra_level" to SettingValue.IntValue(90),
            "future_long" to SettingValue.LongValue(Long.MAX_VALUE),
            "future_double" to SettingValue.DoubleValue(0.25),
            "future_set" to SettingValue.TextSet(setOf("a", "b")),
            "recording_tree_uri" to SettingValue.Text("content://tree/other"),
            "audio_input_key" to SettingValue.Text("usb:Mic")
        )
    )

    private fun library(name: String) = FileLutLibrary(File(tmp.root, name), dispatcher = Dispatchers.Unconfined)

    private fun capabilityFile(identity: DeviceIdentity = pixel): File {
        val store = CapabilityStore(File(tmp.root, "caps-src/${CapabilityStore.FILE_NAME}"))
        store.save(DeviceCapabilities(identity = identity, engineVersion = 1, cameras = emptyList(), complete = true))
        return store.file
    }

    private suspend fun export(password: CharArray?, luts: FileLutLibrary, capabilities: File? = capabilityFile()): File {
        val file = File(tmp.root, "backup-${System.nanoTime()}.zip")
        BackupWriter.write(file.outputStream(), BackupSources(sourceSettings, luts, capabilities), origin, password)
        return file
    }

    private fun expectFailure(reason: BackupException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: BackupException) {
            assertEquals(e.message, reason, e.reason)
        }
    }

    @Test
    fun plainAndEncryptedRoundTripsRestoreEverything() = runBlocking {
        for (pw in listOf(null, password)) {
            val source = library("src-${pw != null}")
            val lookId = source.import(warm, folder = "Looks/Warm").entry.id
            source.import(cool)
            sourceSettings.values["recording_look"] = SettingValue.Text("lib:$lookId")
            val file = export(pw, source)

            val summary = BackupReader().inspect({ file.inputStream() }, pw, pixel)
            assertEquals(pw != null, summary.encrypted)
            assertEquals(2, summary.lutCount)
            assertEquals(listOf("audio_input_key", "recording_tree_uri"), summary.deviceSpecificKeys)
            assertTrue(summary.capabilitiesApply)
            assertEquals("0.7.0", summary.manifest.appVersion)

            val settings = FakeSettings(mapOf("audio_input_key" to SettingValue.Text("usb:Mine")))
            val target = library("dst-${pw != null}")
            val store = CapabilityStore(File(tmp.root, "caps-dst-${pw != null}/${CapabilityStore.FILE_NAME}"))
            val report = BackupReader().restore({
                file.inputStream()
            }, pw, replace = true, File(tmp.root, "staging"), RestoreTargets(settings, target, store, pixel))

            assertEquals(source.snapshots.first(), target.snapshots.first())
            assertEquals(sourceSettings.values - SettingsBackupAccess.DEVICE_SPECIFIC_KEYS, settings.values - SettingsBackupAccess.DEVICE_SPECIFIC_KEYS)
            assertEquals(SettingValue.Text("usb:Mine"), settings.values["audio_input_key"])
            assertEquals(setOf("audio_input_key", "recording_tree_uri"), report.skippedKeys)
            assertEquals(CapabilityBackup.Outcome.RESTORED, report.capabilities)
            assertTrue(store.load() is StoreLoad.Loaded)
            assertFalse("staging is cleaned up", File(tmp.root, "staging").exists())
        }
    }

    @Test
    fun mergeRemapsTheLookToTheLutAlreadyInTheLibrary() = runBlocking {
        val source = library("src")
        val lookId = source.import(cool).entry.id
        sourceSettings.values["monitor_look"] = SettingValue.Text("lib:$lookId")
        val file = export(null, source, capabilities = null)

        val target = library("dst")
        val existing = target.import(cool).entry.id
        val settings = FakeSettings(mapOf("separate_wav" to SettingValue.Bool(true)))
        val report = BackupReader().restore({
            file.inputStream()
        }, null, replace = false, File(tmp.root, "staging"), RestoreTargets(settings, target, null, pixel))

        assertEquals(SettingValue.Text("lib:$existing"), settings.values["monitor_look"])
        assertEquals(SettingValue.Bool(true), settings.values["separate_wav"])
        assertEquals(1, target.snapshots.first().luts.size)
        assertEquals(null, report.capabilities)
    }

    @Test
    fun capabilitiesOfAnotherPhoneAreSkipped() = runBlocking {
        val file = export(null, library("src"))
        val other = pixel.copy(model = "Pixel 9", fingerprint = "google/tokay/tokay:17/BP1A/1:user/release-keys")
        assertFalse(BackupReader().inspect({ file.inputStream() }, null, other).capabilitiesApply)
        val store = CapabilityStore(File(tmp.root, "caps-dst/${CapabilityStore.FILE_NAME}"))
        val report = BackupReader().restore({
            file.inputStream()
        }, null, true, File(tmp.root, "staging"), RestoreTargets(FakeSettings(), library("dst"), store, other))
        assertEquals(CapabilityBackup.Outcome.OTHER_DEVICE, report.capabilities)
        assertEquals(StoreLoad.Missing, store.load())
    }

    @Test
    fun wrongOrMissingPasswordIsRefused() = runBlocking {
        val file = export(password, library("src"))
        expectFailure(BackupException.Reason.WRONG_PASSWORD) { BackupReader().inspect({ file.inputStream() }, "wrong".toCharArray(), pixel) }
        expectFailure(BackupException.Reason.PASSWORD_REQUIRED) { BackupReader().inspect({ file.inputStream() }, null, pixel) }
        val target = library("dst")
        val settings = FakeSettings(mapOf("max_brightness" to SettingValue.Bool(false)))
        try {
            BackupReader().restore({
                file.inputStream()
            }, "wrong".toCharArray(), true, File(tmp.root, "staging"), RestoreTargets(settings, target, null, pixel))
            fail("expected WRONG_PASSWORD")
        } catch (e: BackupException) {
            assertEquals(BackupException.Reason.WRONG_PASSWORD, e.reason)
        }
        assertEquals("nothing changed", mapOf("max_brightness" to SettingValue.Bool(false)), settings.values)
        assertTrue(target.snapshots.first().luts.isEmpty())
    }

    @Test
    fun truncatedOrDamagedFilesAreRefused() = runBlocking {
        for (pw in listOf(null, password)) {
            val source = library("src-${pw != null}")
            source.import(noisy)
            source.import(cool)
            val file = export(pw, source)
            val bytes = file.readBytes()
            assertTrue("big enough for several cipher chunks", bytes.size > 2 * BackupCrypto.CHUNK_BYTES)
            for (cut in listOf(bytes.size / 3, bytes.size / 2, bytes.size - 30, bytes.size - 1)) {
                val cutFile = File(tmp.root, "cut.zip").apply { writeBytes(bytes.copyOf(cut)) }
                expectFailure(BackupException.Reason.CORRUPT) { BackupReader().inspect({ cutFile.inputStream() }, pw, pixel) }
            }
            // Inside the entries' data (a plain zip's central directory is not read: every entry is checked instead).
            for (at in listOf(bytes.size / 4, bytes.size / 2)) {
                val flipped = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x40).toByte() }
                val damaged = File(tmp.root, "damaged.zip").apply { writeBytes(flipped) }
                expectFailure(BackupException.Reason.CORRUPT) { BackupReader().inspect({ damaged.inputStream() }, pw, pixel) }
            }
            val longer = File(tmp.root, "longer.zip").apply { writeBytes(bytes + ByteArray(40)) }
            if (pw != null) expectFailure(BackupException.Reason.CORRUPT) { BackupReader().inspect({ longer.inputStream() }, pw, pixel) }
        }
        val junk = File(tmp.root, "junk.zip").apply { writeText("hello") }
        expectFailure(BackupException.Reason.NOT_A_BACKUP) { BackupReader().inspect({ junk.inputStream() }, null, pixel) }
    }

    @Test
    fun aBackupFromANewerVersionIsRefused() {
        fun craft(manifest: String): File = File(tmp.root, "new-${System.nanoTime()}.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            }
        }
        val base = """"format": "ultimatecamera-backup", "appVersion": "9.0.0", "appVersionCode": 900, "createdAt": "2030-01-01T00:00:00Z",
            "device": {"manufacturer": "x", "model": "y", "fingerprint": "z"}, "entries": []"""
        val newerFormat = craft("""{ $base, "formatVersion": 2, "schemas": {"settings": 1, "lutIndex": 1} }""")
        val newerSettings = craft("""{ $base, "formatVersion": 1, "schemas": {"settings": 2, "lutIndex": 1} }""")
        val newerIndex = craft("""{ $base, "formatVersion": 1, "schemas": {"settings": 1, "lutIndex": 5} }""")
        for (file in listOf(newerFormat, newerSettings, newerIndex)) {
            expectFailure(BackupException.Reason.TOO_NEW) { BackupReader().inspect({ file.inputStream() }, null, pixel) }
        }
    }
}
