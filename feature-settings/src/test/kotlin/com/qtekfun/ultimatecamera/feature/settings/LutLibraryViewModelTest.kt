package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.luts.FileLutLibrary
import com.qtekfun.ultimatecamera.core.luts.ImportResult
import com.qtekfun.ultimatecamera.core.luts.LutFormatName
import com.qtekfun.ultimatecamera.core.luts.LutLibrary
import com.qtekfun.ultimatecamera.core.luts.LutOrigin
import com.qtekfun.ultimatecamera.core.luts.LutSource
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueDownload
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueException
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueLut
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueLutDownloader
import com.qtekfun.ultimatecamera.core.luts.catalogue.DownloadException
import com.qtekfun.ultimatecamera.core.luts.catalogue.LicenceState
import com.qtekfun.ultimatecamera.core.luts.catalogue.LutCatalogue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LutLibraryViewModelTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val tmp = TemporaryFolder(File("build/tmp/lut-vm-test").apply { mkdirs() })

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var library: FileLutLibrary
    private val downloader = FakeDownloader()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        library = FileLutLibrary(File(tmp.root, "luts"), dispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(catalogue: () -> LutCatalogue = { LutCatalogue(1, listOf(LUT)) }) =
        LutLibraryViewModel(library, downloader, catalogue, sourceOf = { error("no SAF on the JVM") })

    private fun test(block: suspend TestScope.(LutLibraryViewModel) -> Unit) = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.snapshot.collect {} }
        block(vm)
    }

    @Test
    fun importLandsInTheOpenFolderAndSaysSo() = test { vm ->
        vm.createFolder("  Film ")
        vm.open("Film")
        vm.importSources(listOf(cube("warm.cube")))
        val message = vm.state.value.message as LibraryMessage.Imported
        assertEquals("warm", message.title)
        assertFalse(message.alreadyPresent)
        assertFalse(vm.state.value.busy)
        assertEquals("Film", vm.snapshot.value!!.luts.single().folder)
        vm.consumeMessage()
        assertNull(vm.state.value.message)
        // The same file again is recognised.
        vm.importSources(listOf(cube("warm copy.cube")))
        assertTrue((vm.state.value.message as LibraryMessage.Imported).alreadyPresent)
    }

    @Test
    fun aBadFileStopsTheImportWithItsReason() = test { vm ->
        vm.importSources(listOf(source("notes.txt", "hello"), cube("never.cube")))
        assertEquals(LibraryMessage.Failed(LibraryProblem.UNSUPPORTED_FORMAT), vm.state.value.message)
        assertTrue(vm.snapshot.value!!.luts.isEmpty())
    }

    @Test
    fun foldersAreCreatedRenamedAndDeleted() = test { vm ->
        vm.createFolder("A")
        vm.open("A")
        vm.createFolder("B")
        assertEquals(listOf("A", "A/B"), vm.snapshot.value!!.folders.sorted())
        vm.renameFolder("A/B", " C ")
        assertEquals(listOf("A", "A/C"), vm.snapshot.value!!.folders.sorted())
        vm.up()
        assertEquals("", vm.state.value.folder)
        vm.deleteFolder("A")
        assertTrue(vm.snapshot.value!!.folders.isEmpty())
        vm.deleteFolder("missing")
        assertEquals(LibraryMessage.Failed(LibraryProblem.NOT_FOUND), vm.state.value.message)
    }

    @Test
    fun entriesAreRenamedDeletedAndGetASwatch() = test { vm ->
        vm.importSources(listOf(cube("warm.cube")))
        val entry = vm.snapshot.value!!.luts.single()
        assertNotNull(vm.swatch(entry.id))
        vm.rename(entry, "Sunset")
        assertEquals("Sunset", vm.snapshot.value!!.luts.single().title)
        vm.delete(entry)
        assertTrue(vm.snapshot.value!!.luts.isEmpty())
        assertNull(vm.swatch(entry.id))
        vm.delete(entry)
        assertEquals(LibraryMessage.Failed(LibraryProblem.NOT_FOUND), vm.state.value.message)
    }

    @Test
    fun aDownloadShowsProgressThenTheResult() = test { vm ->
        assertEquals(listOf(LUT), vm.catalogue)
        assertEquals("example.com", vm.describe(LUT).host)
        val gate = CompletableDeferred<Unit>()
        downloader.onDownload = { progress ->
            progress(50, 200)
            gate.await()
            progress(200, 200)
            library.import(cube("maker.cube"), origin = LutOrigin.catalogue(LUT.id, LUT.download.url))
        }
        vm.download(LUT)
        assertTrue(vm.state.value.busy)
        assertEquals(0.25f, vm.state.value.progress)
        vm.download(LUT) // Ignored while one runs.
        gate.complete(Unit)
        assertEquals(1, downloader.calls)
        assertTrue(downloader.confirmed)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.progress)
        assertEquals("maker", (vm.state.value.message as LibraryMessage.Imported).title)
        assertEquals(setOf(LUT.id), downloadedCatalogueIds(vm.snapshot.value!!.luts))
    }

    @Test
    fun aFailedDownloadSaysWhy() = test { vm ->
        downloader.onDownload = { progress ->
            progress(10, 0) // Unknown size: no fraction.
            throw DownloadException(DownloadException.Reason.CHECKSUM_MISMATCH, "bad")
        }
        vm.download(LUT)
        assertEquals(LibraryMessage.Failed(LibraryProblem.CHECKSUM), vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun anUnreadableCatalogueIsEmpty() = runTest(dispatcher) {
        assertTrue(viewModel { throw CatalogueException("broken") }.catalogue.isEmpty())
        assertTrue(viewModel { throw IOException("gone") }.catalogue.isEmpty())
    }

    @Test
    fun aBrokenLibraryShowsAnEmptySnapshot() = runTest(dispatcher) {
        val broken = object : LutLibrary by library {
            override val snapshots = kotlinx.coroutines.flow.flow<com.qtekfun.ultimatecamera.core.luts.LutLibrarySnapshot> { throw IOException("index") }
        }
        val vm = LutLibraryViewModel(broken, downloader, { LutCatalogue(1, emptyList()) }, sourceOf = { error("unused") })
        backgroundScope.launch { vm.snapshot.collect {} }
        assertTrue(vm.snapshot.value!!.luts.isEmpty())
    }

    class FakeDownloader : CatalogueLutDownloader {
        var onDownload: suspend ((Long, Long) -> Unit) -> ImportResult = { throw IOException("offline") }
        var calls = 0
        var confirmed = false

        override suspend fun download(lut: CatalogueLut, userConfirmed: Boolean, onProgress: (Long, Long) -> Unit): ImportResult {
            calls++
            confirmed = userConfirmed
            return onDownload(onProgress)
        }
    }

    private companion object {
        val LUT = CatalogueLut(
            id = "maker-log-to-709",
            maker = "Maker",
            name = "Log to Rec.709",
            inputCurve = "M-Log",
            output = "Rec.709",
            devices = emptyList(),
            format = LutFormatName.CUBE,
            size = 2,
            licence = LicenceState.LINK_ONLY,
            licenceNotes = "",
            termsUrl = "https://example.com/terms",
            pageUrl = "https://example.com",
            download = CatalogueDownload("https://example.com/lut.cube", "0".repeat(64), 200),
            checkedOn = "2026-10-17"
        )

        /** A valid 2×2×2 identity cube; [name] picks the title. */
        fun cube(name: String) = source(
            name,
            "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { i -> "${i and 1} ${(i shr 1) and 1} ${(i shr 2) and 1}" } + "\n"
        )

        fun source(name: String, text: String) = object : LutSource {
            override val displayName = name

            override fun open() = ByteArrayInputStream(text.toByteArray())
        }
    }
}
