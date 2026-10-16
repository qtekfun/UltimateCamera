package com.qtekfun.ultimatecamera.core.luts.catalogue

import com.qtekfun.ultimatecamera.core.luts.FileLutSource
import com.qtekfun.ultimatecamera.core.luts.ImportResult
import com.qtekfun.ultimatecamera.core.luts.LutLibrary
import com.qtekfun.ultimatecamera.core.luts.LutOrigin
import com.qtekfun.ultimatecamera.core.luts.toHex
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads a catalogue LUT from the maker on demand (REQ-COL-007) and imports it into the library. Nothing is fetched
 * unless the user confirmed (the UI shows maker, size, host and the terms link first: [describe]).
 */
interface CatalogueLutDownloader {
    /** What the confirmation dialog shows. */
    fun describe(lut: CatalogueLut): DownloadPrompt = DownloadPrompt(
        maker = lut.maker,
        name = lut.name,
        host = URI(lut.download.url).host,
        bytes = lut.download.bytes,
        termsUrl = lut.termsUrl,
        licence = lut.licence
    )

    /** Fails with [DownloadException.Reason.NOT_CONFIRMED] unless [userConfirmed]. */
    suspend fun download(lut: CatalogueLut, userConfirmed: Boolean, onProgress: (Long, Long) -> Unit = { _, _ -> }): ImportResult
}

data class DownloadPrompt(val maker: String, val name: String, val host: String, val bytes: Long, val termsUrl: String, val licence: LicenceState)

class DownloadException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        NOT_CONFIRMED,
        NOT_HTTPS,
        HTTP_ERROR,
        NETWORK,
        TOO_LARGE,
        CHECKSUM_MISMATCH,
        ENTRY_MISSING
    }
}

/** The network seam: opens a GET for an https URL. Tests replace it; the app uses [HttpUrlConnectionFetcher]. */
fun interface HttpFetcher {
    fun open(url: String): FetchResponse
}

class FetchResponse(val body: InputStream, val contentLength: Long, private val onClose: () -> Unit = {}) : AutoCloseable {
    override fun close() {
        body.close()
        onClose()
    }
}

/**
 * Plain [HttpURLConnection] (no network library, F-Droid). Only https, redirects followed by hand so a redirect to
 * http is refused, timeouts so a stalled server cannot hang the import.
 */
class HttpUrlConnectionFetcher(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000, private val maxRedirects: Int = 5) :
    HttpFetcher {
    override fun open(url: String): FetchResponse {
        var current = url
        repeat(maxRedirects + 1) {
            requireHttps(current)
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "UltimateCamera")
            }
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw DownloadException(DownloadException.Reason.NETWORK, "cannot reach ${URI(current).host}: ${e.message}", e)
            }
            when (code) {
                in 200..299 -> return FetchResponse(connection.inputStream, connection.contentLengthLong) { connection.disconnect() }
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw DownloadException(DownloadException.Reason.HTTP_ERROR, "redirect without Location")
                    current = URI(current).resolve(location).toString()
                }
                else -> {
                    connection.disconnect()
                    throw DownloadException(DownloadException.Reason.HTTP_ERROR, "HTTP $code from ${URI(current).host}")
                }
            }
        }
        throw DownloadException(DownloadException.Reason.HTTP_ERROR, "too many redirects")
    }
}

internal fun requireHttps(url: String) {
    val scheme = runCatching { URI(url).scheme }.getOrNull()
    if (scheme != "https") throw DownloadException(DownloadException.Reason.NOT_HTTPS, "refusing non-https URL $url")
}

/**
 * Default [CatalogueLutDownloader]. Each download gets its own empty directory under [workDir] (downloaded bytes are
 * untrusted data: they are only hashed, unzipped by exact entry name and parsed, never executed), which is deleted
 * afterwards whatever happens. The file at the URL must match `download.sha256` and size; from a zip only
 * `archiveEntry` is extracted, capped at `entryBytes` and checked against `entrySha256`. The LUT lands in the folder
 * named after the maker, with [LutOrigin.Kind.CATALOGUE].
 */
class DefaultCatalogueLutDownloader(
    private val library: LutLibrary,
    private val workDir: File,
    private val fetcher: HttpFetcher = HttpUrlConnectionFetcher(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : CatalogueLutDownloader {
    override suspend fun download(lut: CatalogueLut, userConfirmed: Boolean, onProgress: (Long, Long) -> Unit): ImportResult {
        if (!userConfirmed) throw DownloadException(DownloadException.Reason.NOT_CONFIRMED, "download of ${lut.id} not confirmed")
        CatalogueValidator.validate(LutCatalogue(LutCatalogue.SCHEMA_VERSION, listOf(lut)))
        val dir = File(workDir, "download-${UUID.randomUUID()}")
        return try {
            val file = withContext(dispatcher) {
                if (!dir.mkdirs()) throw IOException("cannot create $dir")
                val downloaded = File(dir, "download.bin")
                fetch(lut, downloaded, onProgress)
                lut.download.archiveEntry?.let { extract(lut, downloaded, File(dir, "entry.bin")) } ?: downloaded
            }
            library.import(
                FileLutSource(file, lut.fileName),
                folder = lut.maker,
                origin = LutOrigin.catalogue(lut.id, lut.download.url),
                title = lut.name
            )
        } finally {
            withContext(dispatcher) { dir.deleteRecursively() }
        }
    }

    private suspend fun fetch(lut: CatalogueLut, target: File, onProgress: (Long, Long) -> Unit) {
        val expected = lut.download.bytes
        requireHttps(lut.download.url)
        val response = try {
            fetcher.open(lut.download.url)
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            throw DownloadException(DownloadException.Reason.NETWORK, e.message ?: "network error", e)
        }
        response.use {
            if (it.contentLength >
                expected
            ) {
                throw DownloadException(DownloadException.Reason.TOO_LARGE, "server offers ${it.contentLength} bytes, expected $expected")
            }
            val sha = copyCapped(it.body, target, expected) { done -> onProgress(done, expected) }
            if (sha != lut.download.sha256) {
                throw DownloadException(DownloadException.Reason.CHECKSUM_MISMATCH, "${lut.id}: SHA-256 $sha, catalogue says ${lut.download.sha256}")
            }
        }
    }

    private suspend fun extract(lut: CatalogueLut, zip: File, target: File): File {
        val name = lut.download.archiveEntry!!
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            // Only the exact entry name is read; no path from the archive ever reaches the file system.
            if (generateSequence { z.nextEntry }.any { !it.isDirectory && it.name == name }) {
                val sha = copyCapped(z, target, lut.download.entryBytes!!) {}
                if (sha != lut.download.entrySha256) {
                    throw DownloadException(
                        DownloadException.Reason.CHECKSUM_MISMATCH,
                        "${lut.id}: entry SHA-256 $sha, catalogue says ${lut.download.entrySha256}"
                    )
                }
                return target
            }
        }
        throw DownloadException(DownloadException.Reason.ENTRY_MISSING, "${lut.id}: $name not in the archive")
    }

    /** Copies at most [limit] bytes (more is an error, so a wrong or hostile file cannot fill the disk); returns SHA-256. */
    private suspend fun copyCapped(input: InputStream, target: File, limit: Long, progress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    throw DownloadException(DownloadException.Reason.NETWORK, e.message ?: "read failed", e)
                }
                if (n < 0) break
                total += n
                if (total > limit) throw DownloadException(DownloadException.Reason.TOO_LARGE, "more than the expected $limit bytes")
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                progress(total)
            }
        }
        return digest.digest().toHex()
    }
}
