package com.qtekfun.ultimatecamera.core.luts.catalogue

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.CubeLutWriter
import com.qtekfun.ultimatecamera.core.luts.FileLutLibrary
import com.qtekfun.ultimatecamera.core.luts.LutFormatName
import com.qtekfun.ultimatecamera.core.luts.LutOrigin
import com.qtekfun.ultimatecamera.core.luts.sha256Of
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogueDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cubeBytes = CubeLutWriter.write(CubeLut.generate(5, "Maker Rec709") { r, g, b -> Triple(r * r, g, b) }).toByteArray()
    private val entryName = "Maker-tools/Maker-Log-to-Rec709.cube"
    private val zipBytes = zip(mapOf("__MACOSX/Maker-tools/._Maker-Log-to-Rec709.cube" to byteArrayOf(0, 5, 22), entryName to cubeBytes))
    private val url = "https://maker.example/tools.zip"

    private val library by lazy { FileLutLibrary(File(tmp.root, "luts"), dispatcher = Dispatchers.Unconfined) }
    private val work by lazy { File(tmp.root, "work") }
    private var fetches = 0

    private fun downloader(body: ByteArray = zipBytes, declaredLength: Long = body.size.toLong()) =
        DefaultCatalogueLutDownloader(library, work, dispatcher = Dispatchers.Unconfined, fetcher = { requested ->
            fetches++
            assertEquals(url, requested)
            FetchResponse(body.inputStream(), declaredLength)
        })

    private fun entry(
        sha: String = sha256Of(zipBytes.inputStream()),
        bytes: Long = zipBytes.size.toLong(),
        entrySha: String = sha256Of(cubeBytes.inputStream()),
        archiveEntry: String = entryName
    ) = CatalogueLut(
        id = "maker-log-to-rec709",
        maker = "Maker",
        name = "Maker Log to Rec.709",
        inputCurve = "Maker-Log",
        output = "Rec.709",
        devices = listOf("Phone"),
        format = LutFormatName.CUBE,
        size = 5,
        licence = LicenceState.LINK_ONLY,
        licenceNotes = "test",
        termsUrl = "https://maker.example/terms",
        pageUrl = "https://maker.example/luts",
        download = CatalogueDownload(url, sha, bytes, archiveEntry, entrySha, cubeBytes.size.toLong()),
        checkedOn = "2026-10-16"
    )

    @Test
    fun downloadsVerifiesExtractsAndImportsIntoTheMakerFolder() = runBlocking<Unit> {
        val progress = mutableListOf<Long>()
        val result = downloader().download(entry(), userConfirmed = true) { done, _ -> progress += done }
        assertFalse(result.alreadyPresent)
        assertEquals("Maker", result.entry.folder)
        assertEquals("Maker Log to Rec.709", result.entry.title)
        assertEquals("Maker-Log-to-Rec709.cube", result.entry.fileName)
        assertEquals(LutOrigin.catalogue("maker-log-to-rec709", url), result.entry.origin)
        assertEquals(sha256Of(cubeBytes.inputStream()), result.entry.sha256)
        assertEquals(zipBytes.size.toLong(), progress.last())
        assertEquals(listOf("Maker"), library.snapshots.first().folders)
        assertTrue("work dir not cleaned", work.list().isNullOrEmpty())
    }

    @Test
    fun nothingIsFetchedWithoutConfirmation() = runBlocking<Unit> {
        expect(DownloadException.Reason.NOT_CONFIRMED) { downloader().download(entry(), userConfirmed = false) }
        assertEquals(0, fetches)
        val prompt = downloader().describe(entry())
        assertEquals("maker.example", prompt.host)
        assertEquals(LicenceState.LINK_ONLY, prompt.licence)
    }

    @Test
    fun checksumMismatchIsRejectedAndNothingImported() = runBlocking<Unit> {
        expect(DownloadException.Reason.CHECKSUM_MISMATCH) { downloader().download(entry(sha = "0".repeat(64)), userConfirmed = true) }
        expect(DownloadException.Reason.CHECKSUM_MISMATCH) { downloader().download(entry(entrySha = "1".repeat(64)), userConfirmed = true) }
        val tampered = zipBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        expect(DownloadException.Reason.CHECKSUM_MISMATCH) { downloader(body = tampered).download(entry(), userConfirmed = true) }
        assertTrue(library.snapshots.first().luts.isEmpty())
        assertTrue("work dir not cleaned", work.list().isNullOrEmpty())
    }

    @Test
    fun oversizeAndMissingEntryAreRejected() = runBlocking<Unit> {
        expect(DownloadException.Reason.TOO_LARGE) { downloader(declaredLength = zipBytes.size + 1L).download(entry(), userConfirmed = true) }
        // Server sends more than the catalogue size without announcing it: stopped at the cap.
        expect(DownloadException.Reason.TOO_LARGE) { downloader(declaredLength = -1).download(entry(bytes = zipBytes.size - 10L), userConfirmed = true) }
        expect(DownloadException.Reason.ENTRY_MISSING) {
            downloader().download(entry(archiveEntry = "Maker-tools/other.cube"), userConfirmed = true)
        }
        assertTrue(library.snapshots.first().luts.isEmpty())
    }

    @Test
    fun plainHttpIsRefused() {
        val e = runCatching { requireHttps("http://maker.example/tools.zip") }.exceptionOrNull() as DownloadException
        assertEquals(DownloadException.Reason.NOT_HTTPS, e.reason)
    }

    /**
     * Optional check against OPPO's real packages, never committed (link-only): download the two zips from the catalogue
     * URLs into a directory and run with `OPPO_LUT_ZIP_DIR=<dir>`. Verifies every checksum in luts/catalog.json and
     * that both parsers' consumer, the library, accepts OPPO's 65-point files.
     */
    @Test
    fun realOppoPackagesMatchTheCatalogue() = runBlocking<Unit> {
        val dir = System.getenv("OPPO_LUT_ZIP_DIR")?.let(::File)
        assumeTrue("set OPPO_LUT_ZIP_DIR to run", dir?.isDirectory == true)
        val catalogue = LutCatalogue.parse(File("../luts/catalog.json").readText())
        for (lut in catalogue.luts.filter { it.maker == "OPPO" }) {
            val file = File(dir, lut.download.url.substringAfterLast('/'))
            val real = DefaultCatalogueLutDownloader(library, work, dispatcher = Dispatchers.Unconfined, fetcher = {
                FetchResponse(file.inputStream(), file.length())
            })
            val imported = real.download(lut, userConfirmed = true).entry
            assertEquals(lut.id, 65, imported.size)
            println("${lut.id}: ${imported.title} (${imported.bytes} bytes)")
        }
        assertEquals(5, library.snapshots.first().luts.size)
    }

    private suspend fun expect(reason: DownloadException.Reason, block: suspend () -> Unit) {
        val e = try {
            block()
            null
        } catch (e: DownloadException) {
            e
        }
        assertEquals(e?.message, reason, e?.reason)
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
