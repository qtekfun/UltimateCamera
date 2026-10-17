package com.qtekfun.ultimatecamera.core.capabilities

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapabilityBackupTest {
    @get:Rule
    val tmp = TemporaryFolder(File("build/tmp/capability-backup-test").apply { mkdirs() })

    private val pixel = DeviceIdentity("Google", "google", "Pixel 8", "shiba", 37, "BP1A", "google/shiba/shiba:17/BP1A/1:user/release-keys")
    private val caps = DeviceCapabilities(identity = pixel, engineVersion = ProbeEngine.ENGINE_VERSION, cameras = emptyList(), complete = true)

    private fun candidate(): File {
        val file = File(tmp.root, "backup/device-capabilities.json")
        CapabilityStore(file).save(caps)
        return file
    }

    @Test
    fun sameBuildIsRestored() {
        val store = CapabilityStore(File(tmp.root, "store/device-capabilities.json"))
        assertEquals(CapabilityBackup.Outcome.RESTORED, CapabilityBackup.restore(candidate(), store, pixel))
        assertEquals(caps, (store.load() as StoreLoad.Loaded).capabilities)
    }

    @Test
    fun anotherBuildOrPhoneIsSkipped() {
        val store = CapabilityStore(File(tmp.root, "store/device-capabilities.json"))
        val updated = pixel.copy(fingerprint = "google/shiba/shiba:17/BP2A/2:user/release-keys")
        assertEquals(CapabilityBackup.Outcome.OTHER_DEVICE, CapabilityBackup.restore(candidate(), store, updated))
        assertEquals(CapabilityBackup.Outcome.OTHER_DEVICE, CapabilityBackup.restore(candidate(), store, pixel.copy(model = "Pixel 9")))
        assertEquals(StoreLoad.Missing, store.load())
        assertFalse(CapabilityBackup.sameBuild(pixel.copy(fingerprint = ""), pixel.copy(fingerprint = "")))
        assertTrue(CapabilityBackup.sameBuild(pixel, pixel))
    }

    @Test
    fun anUnreadableFileIsSkipped() {
        val file = File(tmp.root, "bad.json").apply { writeText("{not json") }
        val store = CapabilityStore(File(tmp.root, "store/device-capabilities.json"))
        assertEquals(CapabilityBackup.Outcome.UNREADABLE, CapabilityBackup.restore(file, store, pixel))
    }
}
