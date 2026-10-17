package com.qtekfun.ultimatecamera.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLocationTest {
    private val usbUri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AFootage%2FDay%201"
    private val backend = FakeStorageBackend()
    private val store = FakeLocationStore()
    private fun repository() = RecordingLocationRepository(store, backend, Dispatchers.Unconfined)

    @Test
    fun parsesExternalStorageTrees() {
        val usb = SafTree.parse(usbUri)!!
        assertEquals("1A2B-3C4D", usb.volumeId)
        assertEquals("Footage/Day 1", usb.path)
        assertTrue(usb.isLocalStorage)
        assertFalse(usb.isPrimaryVolume)

        val primary = SafTree.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fa")!!
        assertEquals("primary", primary.volumeId)
        assertEquals("Movies", primary.path)
        assertTrue(primary.isPrimaryVolume)

        assertEquals("", SafTree.parse("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A")!!.path)
        assertEquals("a+b é", SafTree.percentDecode("a+b%20%C3%A9"))
    }

    @Test
    fun rejectsNonTreeAndNonLocalUris() {
        assertNull(SafTree.parse("file:///sdcard/Movies"))
        assertNull(SafTree.parse("content://com.android.externalstorage.documents/document/primary%3AMovies"))
        assertNull(SafTree.parse("content://com.android.externalstorage.documents/tree/"))
        assertNull(SafTree.parse("content://com.android.externalstorage.documents/tree/bad%2"))
        val cloud = SafTree.parse("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc")!!
        assertNull(cloud.volumeId)
        assertFalse(cloud.isLocalStorage)
    }

    @Test
    fun storedValueMapsToLocation() {
        assertEquals(RecordingLocation.Default, RecordingLocation.fromStored(null))
        assertEquals(RecordingLocation.Default, RecordingLocation.fromStored(" "))
        assertEquals(RecordingLocation.Folder(usbUri), RecordingLocation.fromStored(usbUri))
    }

    @Test
    fun classifiesVolumes() {
        assertEquals(VolumeKind.INTERNAL, VolumeKind.classify(isPrimary = true, isEmulated = true, isRemovable = false))
        assertEquals(VolumeKind.REMOVABLE, VolumeKind.classify(isPrimary = false, isEmulated = false, isRemovable = true))
        assertEquals(VolumeKind.INTERNAL, VolumeKind.classify(isPrimary = false, isEmulated = false, isRemovable = false))
    }

    @Test
    fun validationChecksInOrder() {
        val validator = LocationValidator(backend)
        val folder = RecordingLocation.Folder(usbUri)
        fun problem() = (validator.validate(folder) as LocationStatus.Unavailable).problem

        assertEquals(
            LocationProblem.UNSUPPORTED_PROVIDER,
            (validator.validate(RecordingLocation.Folder("content://x.provider/tree/a")) as LocationStatus.Unavailable).problem
        )
        assertEquals(LocationProblem.PERMISSION_LOST, problem())
        backend.grants += usbUri
        assertEquals(LocationProblem.VOLUME_UNMOUNTED, problem())
        backend.addUsbFolder(usbUri, "1A2B-3C4D", mounted = false)
        assertEquals(LocationProblem.VOLUME_UNMOUNTED, problem())
        backend.addUsbFolder(usbUri, "1A2B-3C4D")
        backend.existingFolders -= folder
        assertEquals(LocationProblem.FOLDER_MISSING, problem())
        backend.existingFolders += folder
        backend.failCreate = true
        assertEquals(LocationProblem.NOT_WRITABLE, problem())
        backend.failCreate = false

        backend.free[folder] = 1234L
        val ready = validator.validate(folder) as LocationStatus.Ready
        assertEquals(VolumeKind.REMOVABLE, ready.volume!!.kind)
        assertEquals(1234L, ready.freeBytes)
        assertTrue("probe file is removed", backend.listFileNames(folder).isEmpty())
    }

    @Test
    fun readOnlyVolumeIsNotWritable() {
        val folder = backend.addUsbFolder(usbUri, "1A2B-3C4D")
        backend.grants += usbUri
        backend.volumes["1A2B-3C4D"] = backend.volumes.getValue("1A2B-3C4D").copy(readOnly = true)
        assertEquals(LocationStatus.Unavailable(LocationProblem.NOT_WRITABLE), LocationValidator(backend).validate(folder))
    }

    @Test
    fun choosingAUsableFolderStoresItAndReleasesOldGrants() = runTest {
        val old = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        backend.grants += old
        backend.addUsbFolder(usbUri, "1A2B-3C4D")
        val repository = repository()

        assertTrue(repository.choose(usbUri) is LocationStatus.Ready)
        assertEquals(usbUri, store.treeUri.first())
        assertEquals(RecordingLocation.Folder(usbUri), repository.location.first())
        assertEquals(setOf(usbUri), backend.grants)
    }

    @Test
    fun choosingAnUnusableFolderChangesNothing() = runTest {
        val folder = backend.addUsbFolder(usbUri, "1A2B-3C4D")
        backend.existingFolders -= folder
        val repository = repository()

        assertEquals(LocationStatus.Unavailable(LocationProblem.FOLDER_MISSING), repository.choose(usbUri))
        assertNull(store.treeUri.first())
        assertTrue("grant released again", backend.grants.isEmpty())

        val cloud = "content://com.google.android.apps.docs.storage/tree/abc"
        assertEquals(LocationStatus.Unavailable(LocationProblem.UNSUPPORTED_PROVIDER), repository.choose(cloud))
        val refused = "content://com.android.externalstorage.documents/tree/primary%3ANotOffered"
        assertEquals(LocationStatus.Unavailable(LocationProblem.PERMISSION_LOST), repository.choose(refused))
    }

    @Test
    fun checkFallsBackToDefaultWhenTheSsdIsUnplugged() = runTest {
        backend.addUsbFolder(usbUri, "1A2B-3C4D")
        val repository = repository()
        repository.choose(usbUri)

        val plugged = repository.check()
        assertEquals(RecordingLocation.Folder(usbUri), plugged.effective)
        assertFalse(plugged.fellBack)

        backend.volumes.remove("1A2B-3C4D")
        val unplugged = repository.check()
        assertEquals(LocationStatus.Unavailable(LocationProblem.VOLUME_UNMOUNTED), unplugged.status)
        assertEquals(RecordingLocation.Default, unplugged.effective)
        assertTrue(unplugged.fellBack)
        assertTrue(unplugged.effectiveStatus is LocationStatus.Ready)
        assertEquals("the choice is kept for when the SSD comes back", usbUri, store.treeUri.first())
    }

    @Test
    fun checkReportsNowhereToRecord() = runTest {
        backend.failCreate = true
        val check = repository().check()
        assertEquals(RecordingLocation.Default, check.chosen)
        assertNull(check.effective)
        assertFalse(check.fellBack)
    }

    @Test
    fun useDefaultClearsTheChoiceAndEveryGrant() = runTest {
        backend.addUsbFolder(usbUri, "1A2B-3C4D")
        val repository = repository()
        repository.choose(usbUri)
        repository.useDefault()
        assertEquals(RecordingLocation.Default, repository.location.first())
        assertTrue(backend.grants.isEmpty())
    }
}
