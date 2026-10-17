package com.qtekfun.ultimatecamera.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyTest {
    private val gib = 1024L * 1024 * 1024
    private val usbUri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AFootage"
    private val backend = FakeStorageBackend()
    private val store = FakeLocationStore()
    private fun repository() = RecordingLocationRepository(store, backend, Dispatchers.Unconfined)

    private fun drive(name: String, free: Long?, mounted: Boolean = true, readOnly: Boolean = false) =
        Drive(RecordingLocation.Volume(name), VolumeInfo(name, VolumeKind.REMOVABLE, name, mounted, readOnly), free)

    @Test
    fun modeTextRoundTripsAndOlderFilesKeepTheirFolder() {
        listOf(LocationMode.Automatic, LocationMode.Phone, LocationMode.Volume("1a2b-3c4d"), LocationMode.Folder).forEach {
            assertEquals(it, LocationMode.parse(it.stored, treeUri = usbUri))
        }
        assertEquals(LocationMode.Automatic, LocationMode.parse(null, null))
        assertEquals("a folder chosen before D50 stays the location", LocationMode.Folder, LocationMode.parse(null, usbUri))
        assertEquals(LocationMode.Automatic, LocationMode.parse("folder", null))
        assertEquals(LocationMode.Automatic, LocationMode.parse("cloud:x", null))
        assertEquals(LocationMode.Automatic, LocationMode.parse("volume:", null))
    }

    @Test
    fun automaticPicksTheDriveWithMostFreeSpaceElseThePhone() {
        assertEquals(RecordingLocation.Default, LocationPolicy.automaticTarget(emptyList()))
        val small = drive("aaaa-0001", 10 * gib)
        val big = drive("bbbb-0002", 400 * gib)
        val unknown = drive("cccc-0003", null)
        assertEquals(big.location, LocationPolicy.automaticTarget(listOf(small, big, unknown)))
        assertEquals(unknown.location, LocationPolicy.automaticTarget(listOf(unknown)))
        assertEquals(small.location, LocationPolicy.automaticTarget(listOf(unknown, small)))
    }

    @Test
    fun onlyMountedWritableDrivesCount() {
        val volumes = mapOf(
            "ok" to VolumeInfo("ok", VolumeKind.REMOVABLE, "ok", mounted = true),
            "ro" to VolumeInfo("ro", VolumeKind.REMOVABLE, "ro", mounted = true, readOnly = true),
            "ejecting" to VolumeInfo("ejecting", VolumeKind.REMOVABLE, "e", mounted = false)
        )
        val drives = LocationPolicy.drives(listOf("ok", "ro", "ejecting", "gone"), volumes::get) { 5 * gib }
        assertEquals(listOf("ok"), drives.map { it.location.volumeName })
    }

    @Test
    fun explicitChoicesOverrideAutomatic() {
        val drives = listOf(drive("aaaa-0001", 10 * gib))
        assertEquals(RecordingLocation.Default, LocationPolicy.target(LocationMode.Phone, usbUri, drives))
        assertEquals(RecordingLocation.Folder(usbUri), LocationPolicy.target(LocationMode.Folder, usbUri, drives))
        assertEquals(RecordingLocation.Volume("zzzz-9999"), LocationPolicy.target(LocationMode.Volume("zzzz-9999"), null, drives))
        assertEquals(RecordingLocation.Volume("aaaa-0001"), LocationPolicy.target(LocationMode.Automatic, usbUri, drives))
    }

    @Test
    fun freshInstallRecordsToAConnectedDrive() = runTest {
        assertEquals(RecordingLocation.Default, repository().check().effective)
        val ssd = backend.addDrive("1a2b-3c4d", 900 * gib)
        val check = repository().check()
        assertEquals(LocationMode.Automatic, check.mode)
        assertEquals(ssd, check.effective)
        assertFalse(check.fellBack)
    }

    @Test
    fun automaticFallsBackWithAWarningWhenItsDriveIsPulledAndSwitchesWhenOneAppears() = runTest {
        val repository = repository()
        val ssd = backend.addDrive("1a2b-3c4d", 900 * gib)
        assertEquals(ssd, repository.check().effective)

        backend.removeDrive("1a2b-3c4d")
        val pulled = repository.check()
        assertEquals(RecordingLocation.Default, pulled.effective)
        assertTrue("the user is told", pulled.fellBack)
        assertEquals(LocationStatus.Unavailable(LocationProblem.VOLUME_UNMOUNTED), pulled.status)

        val sd = backend.addDrive("5e6f-7a8b", 60 * gib)
        val plugged = repository.check()
        assertEquals(sd, plugged.effective)
        assertFalse(plugged.fellBack)
    }

    @Test
    fun withoutADriveEverSeenAutomaticIsThePhoneWithoutWarning() = runTest {
        val check = repository().check()
        assertEquals(RecordingLocation.Default, check.chosen)
        assertFalse(check.fellBack)
    }

    @Test
    fun aChosenDriveThatIsGoneFallsBackToThePhone() = runTest {
        val repository = repository()
        backend.addDrive("1a2b-3c4d", 900 * gib)
        repository.select(LocationMode.Volume("1a2b-3c4d"))
        assertEquals("volume:1a2b-3c4d", store.mode.first())
        backend.removeDrive("1a2b-3c4d")
        val check = repository.check()
        assertEquals(RecordingLocation.Default, check.effective)
        assertTrue(check.fellBack)
    }

    @Test
    fun phoneOverridesAConnectedDriveAndTheFolderStaysListed() = runTest {
        backend.addUsbFolder(usbUri, "1A2B-3C4D")
        backend.addDrive("1a2b-3c4d", 900 * gib)
        val repository = repository()
        repository.choose(usbUri)
        assertEquals(RecordingLocation.Folder(usbUri), repository.check().effective)

        repository.select(LocationMode.Phone)
        assertEquals(RecordingLocation.Default, repository.check().effective)
        val options = repository.options()
        assertEquals(LocationMode.Phone, options.mode)
        assertEquals(RecordingLocation.Folder(usbUri), options.folder)
        assertEquals(RecordingLocation.Volume("1a2b-3c4d"), options.automaticTarget)
        assertEquals(listOf("1a2b-3c4d"), options.drives.map { it.location.volumeName })
        assertEquals(setOf(usbUri), backend.grants)

        repository.select(LocationMode.Folder)
        assertEquals(RecordingLocation.Folder(usbUri), repository.check().effective)
    }

    @Test
    fun folderCannotBeSelectedWithoutOne() = runTest {
        val repository = repository()
        repository.select(LocationMode.Folder)
        assertNull(store.mode.first())
        assertNull(repository.options().folder)
    }
}
