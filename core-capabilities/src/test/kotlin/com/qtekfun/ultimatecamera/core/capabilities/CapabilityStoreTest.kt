package com.qtekfun.ultimatecamera.core.capabilities

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapabilityStoreTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/capabilities-test").apply { mkdirs() })

    private val file by lazy { File(File(folder.root, "capabilities"), CapabilityStore.FILE_NAME) }
    private val store by lazy { CapabilityStore(file) }

    private val sample = DeviceCapabilities(
        identity = Fixtures.oppo.identity,
        engineVersion = ProbeEngine.ENGINE_VERSION,
        cameras = listOf(
            CameraCapabilities(
                Fixtures.oppo.cameras[0],
                listOf(
                    ProbeResult(
                        cameraId = "0",
                        config = VideoConfig(3840, 2160, 60, CodecType.HEVC, DynamicRange.HLG10, LutMode.HARDWARE),
                        advertised = false,
                        outcome = ProbeOutcome.ACCEPTED_AND_HELD,
                        measuredFps = 60.04,
                        droppedFrames = 0,
                        thermalStatusMax = 0,
                        probedAtEpochMs = 1_760_000_000_000
                    )
                )
            )
        ),
        encoders = Fixtures.oppo.encoders,
        complete = true,
        updatedAtEpochMs = 1_760_000_000_000
    )

    @Test
    fun `missing file`() {
        assertEquals(StoreLoad.Missing, store.load())
    }

    @Test
    fun `round trip through disk`() {
        store.save(sample)
        assertEquals(StoreLoad.Loaded(sample), CapabilityStore(file).load())
        assertTrue(file.readText().contains("\"schemaVersion\": ${DeviceCapabilities.SCHEMA_VERSION}"))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `corrupt file is moved aside and reported, then the store starts empty`() {
        file.parentFile!!.mkdirs()
        file.writeText("{\"schemaVersion\": 1, \"identity\": {tru")
        val load = store.load()
        assertTrue(load is StoreLoad.Corrupt)
        assertEquals(File(file.parentFile, "${file.name}.corrupt"), (load as StoreLoad.Corrupt).movedTo)
        assertTrue(load.movedTo!!.isFile)
        assertEquals(StoreLoad.Missing, store.load())
        store.save(sample)
        assertEquals(StoreLoad.Loaded(sample), store.load())
    }

    @Test
    fun `valid JSON with a wrong shape or an unknown value is corrupt too`() {
        file.parentFile!!.mkdirs()
        listOf(
            "[]",
            "{\"identity\": {}}",
            "{\"schemaVersion\": \"one\"}",
            CapabilityStore.JSON.encodeToString(DeviceCapabilities.serializer(), sample).replace("ACCEPTED_AND_HELD", "SOMETHING_NEW")
        ).forEach { text ->
            file.writeText(text)
            assertTrue(text, store.load() is StoreLoad.Corrupt)
        }
    }

    @Test
    fun `another schema version is not read`() {
        file.parentFile!!.mkdirs()
        file.writeText(CapabilityStore.JSON.encodeToString(DeviceCapabilities.serializer(), sample.copy(schemaVersion = 2)))
        assertEquals(StoreLoad.OtherSchema(2), store.load())
    }

    @Test
    fun `a failed write leaves the previous catalogue intact`() {
        store.save(sample)
        // The temporary file cannot be created: the write fails before anything replaces the catalogue.
        File(file.parentFile, "${file.name}.tmp").mkdirs()
        try {
            store.save(sample.copy(complete = false))
            fail("save should fail")
        } catch (_: java.io.IOException) {
        }
        assertEquals(StoreLoad.Loaded(sample), store.load())
    }

    @Test
    fun `a stale temporary file from a crash is ignored and replaced`() {
        store.save(sample)
        File(file.parentFile, "${file.name}.tmp").writeText("half a fi")
        assertEquals(StoreLoad.Loaded(sample), store.load())
        store.save(sample.copy(complete = false))
        assertEquals(false, (store.load() as StoreLoad.Loaded).capabilities.complete)
    }

    @Test
    fun `loadFor only returns results for the same device, build and engine`() {
        store.save(sample)
        assertNotNull(store.loadFor(Fixtures.oppo.identity))
        assertNull(store.loadFor(Fixtures.oppo.identity.copy(sdk = 37)))
        assertNull(store.loadFor(Fixtures.oppo.identity.copy(fingerprint = "fixture/CPH2841:17/A")))
        assertNull(store.loadFor(Fixtures.pixel8.device.identity))
        assertEquals(ReprobeReason.DEVICE_CHANGED, sample.reprobeReason(Fixtures.pixel8.device.identity))
        assertEquals(ReprobeReason.OS_CHANGED, sample.reprobeReason(Fixtures.oppo.identity.copy(buildId = "other")))
        assertEquals(ReprobeReason.ENGINE_CHANGED, sample.copy(engineVersion = 0).reprobeReason(Fixtures.oppo.identity))
        assertEquals(ReprobeReason.INCOMPLETE, sample.copy(complete = false).reprobeReason(Fixtures.oppo.identity))
        assertNull(sample.reprobeReason(Fixtures.oppo.identity))
    }

    @Test
    fun `clear forgets the catalogue`() {
        store.save(sample)
        store.clear()
        assertEquals(StoreLoad.Missing, store.load())
    }

    @Test
    fun `the app store lives in the no-backup directory`() {
        val noBackup = File(folder.root, "no_backup")
        assertEquals(File(noBackup, "capabilities/${CapabilityStore.FILE_NAME}"), CapabilityStore.inNoBackupDir(noBackup).file)
    }
}
