package com.qtekfun.ultimatecamera.core.luts

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.CubeLutWriter
import com.qtekfun.ultimatecamera.core.gpu.LutFormat
import com.qtekfun.ultimatecamera.core.gpu.ThreeDlLutWriter
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLutLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val warm = CubeLut.generate(9, "Warm") { r, g, b -> Triple((r * 1.1f).coerceAtMost(1f), g, b * 0.9f) }
    private val cool = CubeLut.generate(5) { r, g, b -> Triple(r * 0.9f, g, (b * 1.1f).coerceAtMost(1f)) }
    private val warmCube = BytesLutSource("warm.cube", CubeLutWriter.write(warm).toByteArray())
    private val cool3dl = BytesLutSource("Cool Look.3DL", ThreeDlLutWriter.write(cool).toByteArray())

    private var now = 1_000L
    private fun library(root: File = File(tmp.root, "luts")) = FileLutLibrary(root, dispatcher = Dispatchers.Unconfined, clock = { now++ })

    @Test
    fun importsBothFormatsWithMetadata() = runBlocking<Unit> {
        val lib = library()
        val a = lib.import(warmCube)
        assertFalse(a.alreadyPresent)
        assertEquals("Warm", a.entry.title)
        assertEquals(LutFormat.CUBE, a.entry.lutFormat)
        assertEquals(9, a.entry.size)
        assertEquals(LutOrigin.imported("warm.cube"), a.entry.origin)
        assertEquals(sha256Of(warmCube.open()), a.entry.sha256)
        assertEquals(1_000L, a.entry.importedAtMillis)

        val b = lib.import(cool3dl, folder = "Looks/Cool")
        assertEquals("Cool Look", b.entry.title) // .3dl has no title: the file name without extension.
        assertEquals(LutFormat.THREE_DL, b.entry.lutFormat)
        assertEquals("Looks/Cool", b.entry.folder)

        val snapshot = lib.snapshots.first()
        assertEquals(listOf("Looks", "Looks/Cool"), snapshot.folders)
        assertEquals(listOf(a.entry), snapshot.lutsIn(LutLibrary.ROOT))
        assertEquals(listOf("Looks/Cool"), snapshot.foldersIn("Looks"))
        assertArrayEquals(cool.data, lib.load(b.entry.id).data, 1f / 4095f)
        assertEquals("Cool Look", lib.load(b.entry.id).title)
    }

    @Test
    fun reimportingTheSameBytesReturnsTheExistingEntry() = runBlocking<Unit> {
        val lib = library()
        val first = lib.import(warmCube)
        val again = lib.import(BytesLutSource("copy.cube", CubeLutWriter.write(warm).toByteArray()), folder = "Other")
        assertTrue(again.alreadyPresent)
        assertEquals(first.entry, again.entry)
        assertEquals(1, lib.snapshots.first().luts.size)
        assertEquals(1, File(tmp.root, "luts/files").list()!!.size)
    }

    @Test
    fun renameMoveDeleteAndPersistAcrossInstances() = runBlocking<Unit> {
        val lib = library()
        val id = lib.import(warmCube).entry.id
        lib.rename(id, "  Sunset  ")
        lib.move(id, "Grades/Day")
        lib.swatch(id)
        val reopened = library()
        val entry = reopened.snapshots.first().luts.single()
        assertEquals("Sunset", entry.title)
        assertEquals("Grades/Day", entry.folder)
        assertEquals(listOf("Grades", "Grades/Day"), reopened.snapshots.first().folders)

        assertReason(LutLibraryException.Reason.INVALID_NAME) { reopened.rename(id, " ") }
        reopened.delete(id)
        assertTrue(reopened.snapshots.first().luts.isEmpty())
        assertEquals(0, File(tmp.root, "luts/files").list()!!.size)
        assertEquals(0, File(tmp.root, "luts/swatches").list()!!.size)
        assertReason(LutLibraryException.Reason.NOT_FOUND) { reopened.delete(id) }
    }

    @Test
    fun folderOperations() = runBlocking<Unit> {
        val lib = library()
        lib.createFolder("A/B")
        val id = lib.import(warmCube, folder = "A/B").entry.id
        assertReason(LutLibraryException.Reason.FOLDER_NOT_EMPTY) { lib.deleteFolder("A") }
        assertReason(LutLibraryException.Reason.INVALID_NAME) { lib.renameFolder("A", "A/B/C") }
        assertReason(LutLibraryException.Reason.INVALID_NAME) { lib.createFolder("../escape") }
        assertReason(LutLibraryException.Reason.INVALID_NAME) { lib.createFolder("a//b") }
        assertReason(LutLibraryException.Reason.INVALID_NAME) { lib.createFolder("") }
        lib.createFolder("Z")
        assertReason(LutLibraryException.Reason.FOLDER_EXISTS) { lib.renameFolder("A", "Z") }

        lib.renameFolder("A", "Z/A2")
        var snapshot = lib.snapshots.first()
        assertEquals(listOf("Z", "Z/A2", "Z/A2/B"), snapshot.folders)
        assertEquals("Z/A2/B", snapshot.luts.single { it.id == id }.folder)

        lib.createFolder("Empty")
        lib.deleteFolder("Empty")
        lib.deleteFolder("Z", recursive = true)
        snapshot = lib.snapshots.first()
        assertTrue(snapshot.folders.isEmpty())
        assertTrue(snapshot.luts.isEmpty())
        assertEquals(0, File(tmp.root, "luts/files").list()!!.size)
    }

    @Test
    fun rejectsBadFilesAndLeavesNothingBehind() = runBlocking<Unit> {
        val lib = library()
        assertReason(LutLibraryException.Reason.UNSUPPORTED_FORMAT) { lib.import(BytesLutSource("photo.jpg", byteArrayOf(1, 2, 3))) }
        val broken = assertReason(LutLibraryException.Reason.INVALID_LUT) {
            lib.import(BytesLutSource("broken.cube", "LUT_3D_SIZE 2\n0 0 0\n".toByteArray()))
        }
        assertTrue(broken.message, broken.message!!.contains("expected 8 entries"))
        val small = FileLutLibrary(File(tmp.root, "luts"), dispatcher = Dispatchers.Unconfined, maxFileBytes = 100)
        assertReason(LutLibraryException.Reason.TOO_LARGE) { small.import(warmCube) }
        assertTrue(small.snapshots.first().luts.isEmpty())
        assertEquals(0, File(tmp.root, "luts/files").list()!!.size)
        assertEquals(0, File(tmp.root, "luts/tmp").list()!!.size)
    }

    @Test
    fun indexIsVersionedJsonWrittenAtomically() = runBlocking<Unit> {
        val lib = library()
        lib.import(warmCube)
        val root = File(tmp.root, "luts")
        val text = File(root, FileLutLibrary.INDEX_FILE).readText()
        assertTrue(text, text.contains("\"schemaVersion\": 1"))
        assertTrue(text, text.contains("\"format\": \"cube\""))
        assertFalse(File(root, "index.json.tmp").exists())
    }

    @Test
    fun corruptIndexIsSetAsideAndRebuiltFromTheFiles() = runBlocking<Unit> {
        val root = File(tmp.root, "luts")
        val id = library(root).import(warmCube, folder = "Keep").entry.id
        File(root, FileLutLibrary.INDEX_FILE).writeText("{ not json")
        val recovered = library(root).snapshots.first().luts.single()
        assertEquals(id, recovered.id)
        assertEquals(LutOrigin.Kind.RECOVERED, recovered.origin.kind)
        assertEquals("Warm", recovered.title)
        assertEquals(sha256Of(warmCube.open()), recovered.sha256)
        assertTrue(File(root, "index.json.corrupt").isFile)
    }

    @Test
    fun orphanFilesAreAdoptedAndMissingFilesDropped() = runBlocking<Unit> {
        val root = File(tmp.root, "luts")
        val lib = library(root)
        val gone = lib.import(warmCube).entry
        File(root, "files/${gone.id}.cube").delete()
        File(root, "files/orphan.3dl").writeText(ThreeDlLutWriter.write(cool))
        File(root, "files/junk.cube").writeText("garbage")
        val luts = library(root).snapshots.first().luts
        assertEquals(listOf("orphan"), luts.map { it.id })
        assertFalse(File(root, "files/junk.cube").exists())
    }

    @Test
    fun newerIndexIsRefusedAndNotOverwritten() = runBlocking<Unit> {
        val root = File(tmp.root, "luts").apply { mkdirs() }
        val newer = """{"schemaVersion": 99, "folders": [], "luts": []}"""
        File(root, FileLutLibrary.INDEX_FILE).writeText(newer)
        assertReason(LutLibraryException.Reason.INDEX_TOO_NEW) { library(root).snapshots.first() }
        assertEquals(newer, File(root, FileLutLibrary.INDEX_FILE).readText())
    }

    @Test
    fun swatchIsRenderedLazilyWithTheCpuReference() = runBlocking<Unit> {
        val lib = library()
        val id = lib.import(warmCube).entry.id
        val swatchDir = File(tmp.root, "luts/swatches")
        assertEquals(0, swatchDir.list()!!.size)
        val file = lib.swatch(id)
        val image = ImageIO.read(file)
        assertEquals(LutSwatch.WIDTH, image.width)
        assertEquals(LutSwatch.HEIGHT, image.height)
        val expected = LutSwatch.pixels(lib.load(id)) // the stored file (6-decimal text), not the in-memory LUT
        val actual = image.getRGB(0, 0, LutSwatch.WIDTH, LutSwatch.HEIGHT, null, 0, LutSwatch.WIDTH)
        assertArrayEquals(expected, actual)
        assertNotEquals(LutSwatch.input().toList(), actual.toList())
        val modified = file.lastModified()
        assertEquals(modified, lib.swatch(id).lastModified()) // Cached, not re-rendered.
    }

    private suspend fun assertReason(reason: LutLibraryException.Reason, block: suspend () -> Unit): LutLibraryException {
        try {
            block()
        } catch (e: LutLibraryException) {
            assertEquals(e.message, reason, e.reason)
            return e
        }
        fail("expected $reason")
        error("unreachable")
    }
}
