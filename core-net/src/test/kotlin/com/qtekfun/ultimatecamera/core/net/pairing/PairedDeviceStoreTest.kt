package com.qtekfun.ultimatecamera.core.net.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairedDeviceStoreTest {
    // On disk under build/, not the tmpfs /tmp of the dev machine.
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/unit-test-files").apply { mkdirs() })

    private fun device(name: String = "Monitor") = PairedDevice(
        id = Secrets.newDeviceId(),
        name = name,
        role = DeviceRole.MONITOR,
        key = B64.encode(Secrets.randomBytes(Secrets.KEY_BYTES)),
        endpoints = listOf(Endpoint("192.168.1.30", 47_802)),
        pairedAtEpochMs = 1_790_000_000_000L
    )

    @Test
    fun missingFileGivesAnEmptyListWithANewOwnId() {
        val store = PairedDeviceStore(File(temp.root, PairedDeviceStore.FILE_NAME))
        val load = store.load()
        assertTrue(load is PairedDevicesLoad.Ok && load.created)
        assertTrue(Secrets.isValidDeviceId(load.file.ownId))
        assertTrue(load.file.devices.isEmpty())
    }

    @Test
    fun roundTripsAtomicallyAndLeavesNoTempFile() {
        val file = File(temp.root, "sub/${PairedDeviceStore.FILE_NAME}")
        val store = PairedDeviceStore(file)
        val content = PairedDevices.upsert(store.load().file, device())
        store.save(content)
        val reloaded = PairedDeviceStore(file).load()
        assertTrue(reloaded is PairedDevicesLoad.Ok && !reloaded.created)
        assertEquals(content, reloaded.file)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        assertTrue(file.readText().contains("\"schema\": 1"))
    }

    @Test
    fun corruptFileIsSetAsideAndAnEmptyListUsed() {
        val file = File(temp.root, PairedDeviceStore.FILE_NAME)
        file.writeText("{\"schema\":1,\"own_id\":")
        val load = PairedDeviceStore(file).load()
        assertTrue(load is PairedDevicesLoad.Recovered)
        assertTrue(File(file.path + ".corrupt").exists())
        assertTrue(load.file.devices.isEmpty())
    }

    @Test
    fun invalidKeyCountsAsCorrupt() {
        val file = File(temp.root, PairedDeviceStore.FILE_NAME)
        val own = Secrets.newDeviceId()
        file.writeText("""{"schema":1,"own_id":"$own","devices":[{"id":"$own","name":"x","role":"camera","key":"short","pairedAtEpochMs":1}]}""")
        assertTrue(PairedDeviceStore(file).load() is PairedDevicesLoad.Recovered)
    }

    @Test(expected = IllegalStateException::class)
    fun newerSchemaIsReadButNeverOverwritten() {
        val file = File(temp.root, PairedDeviceStore.FILE_NAME)
        val own = Secrets.newDeviceId()
        file.writeText("""{"schema":7,"own_id":"$own","devices":[],"future_field":true}""")
        val store = PairedDeviceStore(file)
        val load = store.load()
        assertTrue(load is PairedDevicesLoad.NewerSchema && load.schema == 7)
        assertEquals(own, load.file.ownId)
        store.save(load.file)
    }

    @Test
    fun editsReplaceRemoveAndTouch() {
        val a = device("A")
        val b = device("B")
        var file = PairedDevicesFile(ownId = Secrets.newDeviceId())
        file = PairedDevices.upsert(PairedDevices.upsert(file, a), b)
        val rekeyed = a.copy(key = B64.encode(Secrets.randomBytes(Secrets.KEY_BYTES)))
        file = PairedDevices.upsert(file, rekeyed)
        assertEquals(2, file.devices.size)
        assertEquals(rekeyed.key, file.devices.single { it.id == a.id }.key)
        file = PairedDevices.touch(file, b.id, listOf(Endpoint("10.0.0.9", 1)), 42L)
        assertEquals(42L, file.devices.single { it.id == b.id }.lastSeenEpochMs)
        assertEquals(listOf(Endpoint("10.0.0.9", 1)), file.devices.single { it.id == b.id }.endpoints)
        file = PairedDevices.remove(file, a.id)
        assertEquals(listOf(b.id), file.devices.map { it.id })
        assertFalse(rekeyed.toString().contains(rekeyed.key))
    }
}
