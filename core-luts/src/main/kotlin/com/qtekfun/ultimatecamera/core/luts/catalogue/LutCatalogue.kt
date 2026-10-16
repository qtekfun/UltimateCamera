package com.qtekfun.ultimatecamera.core.luts.catalogue

import android.content.res.AssetManager
import com.qtekfun.ultimatecamera.core.luts.LutFormatName
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The makers' LUT catalogue, `luts/catalog.json` (REQ-COL-006). Its schema is `luts/catalog.schema.json`; [parse]
 * enforces the same rules plus the licence policy of REQ-COL-007/008 (only `redistributable` entries may name a bundled
 * file, and they must carry the licence notice).
 */
@Serializable
data class LutCatalogue(val schemaVersion: Int, val luts: List<CatalogueLut>) {
    fun find(id: String): CatalogueLut? = luts.firstOrNull { it.id == id }

    companion object {
        const val SCHEMA_VERSION = 1
        const val ASSET_NAME = "catalog.json"

        private val json = Json { ignoreUnknownKeys = false }

        /** Parses and validates; any problem is a [CatalogueException] naming the entry. */
        fun parse(text: String): LutCatalogue {
            val catalogue = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw CatalogueException("catalogue is not valid: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw CatalogueException("catalogue is not valid: ${e.message}", e)
            }
            CatalogueValidator.validate(catalogue)
            return catalogue
        }

        /** The copy shipped in the APK (core-luts packages the repository's `luts/` directory as assets). */
        fun fromAssets(assets: AssetManager): LutCatalogue = parse(assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
    }
}

@Serializable
data class CatalogueLut(
    /** Stable id, lower-case `[a-z0-9-]`, e.g. `oppo-o-log2-to-rec709`. */
    val id: String,
    val maker: String,
    val name: String,
    /** The Log curve the LUT expects as input, e.g. `O-Log2`. */
    val inputCurve: String,
    /** What it produces, e.g. `Rec.709 Gamma 2.4 D65`. */
    val output: String,
    /** Phones known to record [inputCurve]; informational. */
    val devices: List<String>,
    val format: LutFormatName,
    /** Lattice points per axis. */
    val size: Int,
    val licence: LicenceState,
    /** What the terms say, in one or two sentences, with the reason for [licence]. */
    val licenceNotes: String,
    val termsUrl: String,
    /** The official page that links the download. */
    val pageUrl: String,
    val download: CatalogueDownload,
    /** Path inside the repository of a bundled copy; only for [LicenceState.REDISTRIBUTABLE]. */
    val bundledPath: String? = null,
    /** ISO date the URL, checksums and terms were last verified. */
    val checkedOn: String
) {
    /** Name the LUT is stored under in the library. */
    val fileName: String get() = download.archiveEntry?.substringAfterLast('/') ?: download.url.substringAfterLast('/')
}

/**
 * Where the official file is. [sha256] and [bytes] describe the file at [url]. When the maker ships a zip, [archiveEntry]
 * is the LUT's path inside it and [entrySha256] / [entryBytes] describe that file.
 */
@Serializable
data class CatalogueDownload(
    val url: String,
    val sha256: String,
    val bytes: Long,
    val archiveEntry: String? = null,
    val entrySha256: String? = null,
    val entryBytes: Long? = null
)

/** REQ-COL-006 licence states. */
@Serializable
enum class LicenceState {
    /** The terms allow redistribution: may be bundled in the repo/APK with its notice (REQ-COL-008). */
    @SerialName("redistributable")
    REDISTRIBUTABLE,

    /** May only be linked: the app downloads it from the maker on request (REQ-COL-007). */
    @SerialName("link-only")
    LINK_ONLY,

    /** Terms not found or unclear: treated as link-only. */
    @SerialName("unknown")
    UNKNOWN
}

class CatalogueException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal object CatalogueValidator {
    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024

    fun validate(c: LutCatalogue) {
        if (c.schemaVersion != LutCatalogue.SCHEMA_VERSION) {
            throw CatalogueException("unsupported catalogue schemaVersion ${c.schemaVersion} (expected ${LutCatalogue.SCHEMA_VERSION})")
        }
        val seen = HashSet<String>()
        for (lut in c.luts) {
            fun fail(message: String): Nothing = throw CatalogueException("${lut.id}: $message")
            if (!ID.matches(lut.id)) fail("id must match ${ID.pattern}")
            if (!seen.add(lut.id)) fail("duplicate id")
            if (listOf(lut.maker, lut.name, lut.inputCurve, lut.output).any { it.isBlank() }) fail("empty text field")
            if (lut.size !in 2..256) fail("size out of range")
            if (lut.licenceNotes.isBlank()) fail("licenceNotes is required")
            if (!DATE.matches(lut.checkedOn)) fail("checkedOn must be YYYY-MM-DD")
            listOf(lut.termsUrl, lut.pageUrl, lut.download.url).forEach { if (!isHttps(it)) fail("not an https URL: $it") }
            checkDownload(lut, ::fail)
            when (lut.licence) {
                LicenceState.REDISTRIBUTABLE -> if (lut.bundledPath.isNullOrBlank()) fail("redistributable entries name their bundled file")
                // REQ-COL-007: never in the repo or the APK.
                LicenceState.LINK_ONLY, LicenceState.UNKNOWN -> if (lut.bundledPath != null) fail("${lut.licence} LUTs must not be bundled")
            }
        }
    }

    private fun checkDownload(lut: CatalogueLut, fail: (String) -> Nothing) {
        val d = lut.download
        if (!SHA256.matches(d.sha256)) fail("sha256 must be 64 lower-case hex digits")
        if (d.bytes !in 1..MAX_DOWNLOAD_BYTES) fail("bytes out of range")
        val archived = listOf(d.archiveEntry, d.entrySha256, d.entryBytes)
        if (archived.any { it != null } && archived.any { it == null }) fail("archiveEntry, entrySha256 and entryBytes go together")
        val fileName = d.archiveEntry ?: d.url.substringAfterLast('/')
        d.archiveEntry?.let { entry ->
            if (!d.url.endsWith(".zip", ignoreCase = true)) fail("archiveEntry needs a .zip download")
            if (entry.startsWith("/") || entry.split('/').any { it == ".." || it.isEmpty() }) fail("unsafe archiveEntry")
            if (!SHA256.matches(d.entrySha256!!)) fail("entrySha256 must be 64 lower-case hex digits")
            if (d.entryBytes!! !in 1..MAX_DOWNLOAD_BYTES) fail("entryBytes out of range")
        }
        if (!fileName.lowercase().endsWith(".${lut.format.format.extension}")) fail("file $fileName does not match format ${lut.format}")
    }

    private fun isHttps(url: String): Boolean = runCatching { URI(url) }
        .onFailure { if (it !is URISyntaxException) throw it }
        .getOrNull()
        ?.let { it.scheme == "https" && !it.host.isNullOrEmpty() } == true
}
