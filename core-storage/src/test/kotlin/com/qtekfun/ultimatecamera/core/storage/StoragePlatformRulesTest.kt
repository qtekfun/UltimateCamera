package com.qtekfun.ultimatecamera.core.storage

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePlatformRulesTest {
    private val usbUri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AFootage%2FDay%201"
    private val primaryUri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val cloudUri = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc"

    @Test
    fun filesGoToTheirMediaCollectionFolder() {
        assertEquals(MediaKind.VIDEO, StoragePlatformRules.mediaKind("video/mp4"))
        assertEquals(MediaKind.AUDIO, StoragePlatformRules.mediaKind("audio/x-wav"))
        assertEquals(MediaKind.IMAGE, StoragePlatformRules.mediaKind("image/jpeg"))
        assertEquals("Movies/UltimateCamera/", MediaKind.VIDEO.relativePath)
        assertEquals("Recordings/UltimateCamera/", MediaKind.AUDIO.relativePath)
        assertEquals("Pictures/UltimateCamera/", MediaKind.IMAGE.relativePath)
    }

    @Test
    fun otherTypesAreRefusedWithTheContractException() {
        val e = assertThrows(IOException::class.java) { StoragePlatformRules.mediaKind("application/octet-stream") }
        assertEquals("the default location takes video, audio or images, not application/octet-stream", e.message)
        assertThrows(IOException::class.java) { StoragePlatformRules.mediaKind("text/plain") }
    }

    @Test
    fun mediaStoreVolumeIsTheDrivesOrThePrimary() {
        assertEquals("1a2b-3c4d", StoragePlatformRules.mediaStoreVolume(RecordingLocation.Volume("1a2b-3c4d"), "external_primary"))
        assertEquals("external_primary", StoragePlatformRules.mediaStoreVolume(RecordingLocation.Default, "external_primary"))
    }

    @Test
    fun freeSpaceSourcePerLocation() {
        assertEquals(FreeSpaceSource.DocumentsRoot("primary"), StoragePlatformRules.freeSpaceSource(RecordingLocation.Default))
        assertEquals(FreeSpaceSource.DocumentsRoot("1A2B-3C4D"), StoragePlatformRules.freeSpaceSource(RecordingLocation.Folder(usbUri)))
        assertEquals(FreeSpaceSource.DocumentsRoot("primary"), StoragePlatformRules.freeSpaceSource(RecordingLocation.Folder(primaryUri)))
        assertEquals(FreeSpaceSource.Volume("1a2b-3c4d"), StoragePlatformRules.freeSpaceSource(RecordingLocation.Volume("1a2b-3c4d")))
        // A cloud folder or a malformed URI has no local volume.
        assertSame(FreeSpaceSource.Unknown, StoragePlatformRules.freeSpaceSource(RecordingLocation.Folder(cloudUri)))
        assertSame(FreeSpaceSource.Unknown, StoragePlatformRules.freeSpaceSource(RecordingLocation.Folder("not a uri")))
    }

    @Test
    fun volumesMatchBySafUuidOrMediaStoreName() {
        assertTrue("SAF id in upper case", StoragePlatformRules.volumeMatches("1A2B-3C4D", "1A2B-3C4D", "1a2b-3c4d"))
        assertTrue("UUID case differs", StoragePlatformRules.volumeMatches("1a2b-3c4d", "1A2B-3C4D", null))
        assertTrue("MediaStore name", StoragePlatformRules.volumeMatches("external_primary", null, "external_primary"))
        assertFalse(StoragePlatformRules.volumeMatches("5555-6666", "1A2B-3C4D", "1a2b-3c4d"))
        assertFalse("the primary volume has no UUID", StoragePlatformRules.volumeMatches("primary", null, "external_primary"))
    }

    @Test
    fun mountedStates() {
        assertTrue(StoragePlatformRules.mounted("mounted"))
        assertTrue(StoragePlatformRules.mounted("mounted_ro"))
        assertFalse(StoragePlatformRules.mounted("unmounted"))
        assertFalse(StoragePlatformRules.mounted("ejecting"))
        assertFalse(StoragePlatformRules.mounted(null))
        assertTrue(StoragePlatformRules.readOnly("mounted_ro"))
        assertFalse(StoragePlatformRules.readOnly("mounted"))
    }

    @Test
    fun rootFreeBytesIsTheFirstMatchingRootThatReportsIt() {
        val roots = sequenceOf<Pair<String?, Long?>>(
            "home" to 1L,
            null to 2L,
            "1A2B-3C4D" to null,
            "1a2b-3c4d" to 64_000_000_000L,
            "1A2B-3C4D" to 5L
        )
        assertEquals(64_000_000_000L, StoragePlatformRules.rootAvailableBytes(roots, "1A2B-3C4D"))
        assertNull(StoragePlatformRules.rootAvailableBytes(roots, "primary"))
        assertNull(StoragePlatformRules.rootAvailableBytes(emptySequence(), "primary"))
    }

    @Test
    fun removableVolumesLeaveOutThePhoneAndAreSorted() {
        val names = listOf("external_primary", "zzzz-0002", "external", "aaaa-0001", "bbbb-sd")
        val removable = StoragePlatformRules.removableVolumeNames(names, setOf("external_primary", "external")) { it != "bbbb-sd" }
        assertEquals(listOf("aaaa-0001", "zzzz-0002"), removable)
        assertTrue(StoragePlatformRules.removableVolumeNames(names, setOf("external_primary", "external")) { false }.isEmpty())
    }

    @Test
    fun everyDocumentWithTheNameIsFound() {
        val children = listOf("id1" to "a.mp4", "id2" to "speedtest.bin", "id3" to null, "id4" to "speedtest.bin")
        assertEquals(listOf("id2", "id4"), StoragePlatformRules.documentsNamed(children, "speedtest.bin"))
        assertTrue(StoragePlatformRules.documentsNamed(children, "missing.mp4").isEmpty())
    }

    @Test
    fun platformFailuresBecomeIoExceptionsWithTheirCause() {
        listOf(SecurityException("grant revoked"), IllegalArgumentException("bad uri"), IllegalStateException("closed"), UnsupportedOperationException("no"))
            .forEach { failure ->
                val e = assertThrows(IOException::class.java) { StoragePlatformRules.io<Unit> { throw failure } }
                assertSame(failure, e.cause)
                assertEquals(failure.message, e.message)
            }
        assertEquals(42, StoragePlatformRules.io { 42 })
        // Already the contract's type, or an unrelated error: passed through untouched.
        val io = IOException("disk full")
        assertSame(io, assertThrows(IOException::class.java) { StoragePlatformRules.io<Unit> { throw io } })
        assertThrows(ArithmeticException::class.java) { StoragePlatformRules.io<Unit> { throw ArithmeticException() } }
    }

    @Test
    fun locationLabels() {
        val strings = mapOf(
            R.string.storage_mode_phone to "Phone storage",
            R.string.storage_drive_unnamed to "USB drive or SD card",
            R.string.storage_folder_root to "root of the drive",
            R.string.storage_folder_named to "Folder: %1\$s"
        )
        val string: (Int, Array<out Any>) -> String = { id, args -> strings.getValue(id).format(*args) }
        assertEquals("Phone storage", LocationLabels.location(string, RecordingLocation.Default))
        assertEquals("Folder: Footage/Day 1", LocationLabels.location(string, RecordingLocation.Folder(usbUri)))
        assertEquals(
            "Folder: root of the drive",
            LocationLabels.location(string, RecordingLocation.Folder("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"))
        )
        val drive = RecordingLocation.Volume("1a2b-3c4d")
        val named = Drive(drive, VolumeInfo("1a2b-3c4d", VolumeKind.REMOVABLE, "SanDisk USB drive", mounted = true), 1L)
        assertEquals("SanDisk USB drive", LocationLabels.location(string, drive, drives = listOf(named)))
        assertEquals("USB drive or SD card", LocationLabels.location(string, drive))
        // An explicit volume wins over the list; a blank system name falls back.
        val blank = VolumeInfo("1a2b-3c4d", VolumeKind.REMOVABLE, " ", mounted = true)
        assertEquals("USB drive or SD card", LocationLabels.location(string, drive, drives = listOf(named), volume = blank))
    }
}
