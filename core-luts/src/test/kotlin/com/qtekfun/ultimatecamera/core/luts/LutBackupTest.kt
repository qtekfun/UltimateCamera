package com.qtekfun.ultimatecamera.core.luts

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.CubeLutWriter
import java.io.File
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

class LutBackupTest {
    // On disk under the module's build dir (the dev machine's /tmp is tmpfs).
    @get:Rule
    val tmp = TemporaryFolder(File("build/tmp/lut-backup-test").apply { mkdirs() })

    private val warm = BytesLutSource("warm.cube", CubeLutWriter.write(CubeLut.generate(5, "Warm") { r, g, b -> Triple(r, g, b * 0.9f) }).toByteArray())
    private val cool = BytesLutSource("cool.cube", CubeLutWriter.write(CubeLut.generate(5, "Cool") { r, g, b -> Triple(r * 0.9f, g, b) }).toByteArray())
    private val green = BytesLutSource("green.cube", CubeLutWriter.write(CubeLut.generate(5, "Green") { r, g, b -> Triple(r, g * 0.8f, b) }).toByteArray())

    private fun library(name: String) = FileLutLibrary(File(tmp.root, name), dispatcher = Dispatchers.Unconfined)

    /** Copies every file of [view] to a staging dir, as the backup reader does. */
    private fun stage(view: LutBackupView): List<StagedLut> = view.luts.map { entry ->
        val file = File(tmp.root, "staged-${entry.id}")
        view.open(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
        StagedLut(entry, file)
    }

    @Test
    fun replaceRestoresTheSameLibraryWithTheSameIds() = runBlocking<Unit> {
        val source = library("a")
        source.import(warm, folder = "Looks/Warm")
        source.import(cool)
        source.createFolder("Empty")
        val view = source.backupView()
        val (folders, luts) = LutIndexCodec.decode(view.indexJson())

        val target = library("b")
        target.import(green)
        val ids = target.restore(folders, stage(view).map { it.copy(entry = luts.first { e -> e.id == it.entry.id }) }, replace = true)

        val snapshot = target.snapshots.first()
        assertEquals(source.snapshots.first(), snapshot)
        assertTrue(ids.all { (from, to) -> from == to })
        assertEquals("Warm", target.load(luts.first { it.folder == "Looks/Warm" }.id).title)
        assertEquals("green's file is gone", 2, File(tmp.root, "b/files").listFiles()!!.size)
    }

    @Test
    fun mergeKeepsExistingLutsAndMapsDuplicates() = runBlocking<Unit> {
        val source = library("a")
        val warmId = source.import(warm).entry.id
        source.import(cool)
        val target = library("b")
        val existingWarm = target.import(warm).entry.id
        target.import(green)

        val ids = target.restore(emptyList(), stage(source.backupView()), replace = false)

        assertEquals(existingWarm, ids[warmId])
        assertEquals(3, target.snapshots.first().luts.size)
    }

    @Test
    fun aStagedFileThatDoesNotMatchItsChecksumChangesNothing() = runBlocking<Unit> {
        val source = library("a")
        source.import(warm)
        val staged = stage(source.backupView()).single()
        staged.file.writeText(String(cool.open().readBytes()))
        val target = library("b")
        target.import(green)
        try {
            target.restore(emptyList(), listOf(staged), replace = true)
            fail("expected a checksum error")
        } catch (e: LutLibraryException) {
            assertEquals(LutLibraryException.Reason.INVALID_LUT, e.reason)
        }
        assertEquals(listOf("Green"), target.snapshots.first().luts.map { it.title })
    }

    @Test
    fun unsafeIdsGetANewId() = runBlocking<Unit> {
        val source = library("a")
        source.import(warm)
        val staged = stage(source.backupView()).single().let { it.copy(entry = it.entry.copy(id = "../evil")) }
        val ids = library("b").restore(emptyList(), listOf(staged), replace = false)
        assertFalse(ids.getValue("../evil").contains('/'))
        assertFalse(File(tmp.root, "evil.cube").exists())
    }

    @Test
    fun aNewerIndexSchemaIsRefused() {
        try {
            LutIndexCodec.decode("""{"schemaVersion": 99, "folders": [], "luts": []}""")
            fail("expected INDEX_TOO_NEW")
        } catch (e: LutLibraryException) {
            assertEquals(LutLibraryException.Reason.INDEX_TOO_NEW, e.reason)
        }
    }
}
