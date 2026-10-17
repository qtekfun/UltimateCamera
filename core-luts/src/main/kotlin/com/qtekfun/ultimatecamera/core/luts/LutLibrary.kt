package com.qtekfun.ultimatecamera.core.luts

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutFormat
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user's LUT library (REQ-COL-003): LUT files copied into app-private storage, organised in folders, each validated
 * by parsing on import. Folders are paths such as `"OPPO"` or `"Looks/Film"`; `""` is the root. All operations run off
 * the main thread and every change is persisted before it is visible in [snapshots].
 */
interface LutLibrary {
    /** The current library, then every change. */
    val snapshots: Flow<LutLibrarySnapshot>

    /**
     * Copies [source] into the library after validating it. Importing a file already present returns the existing entry.
     * The title is [title], else the LUT's own TITLE, else the file name without extension.
     */
    suspend fun import(
        source: LutSource,
        folder: String = ROOT,
        origin: LutOrigin = LutOrigin.imported(source.displayName),
        title: String? = null
    ): ImportResult

    suspend fun rename(id: String, title: String): LutEntry

    suspend fun move(id: String, folder: String): LutEntry

    suspend fun delete(id: String)

    /** Creates [path] and its missing parents. Creating an existing folder is a no-op. */
    suspend fun createFolder(path: String)

    /** Renames or moves a folder with its sub-folders and LUTs. */
    suspend fun renameFolder(from: String, to: String)

    /** Deletes an empty folder, or with [recursive] its sub-folders and LUTs too. */
    suspend fun deleteFolder(path: String, recursive: Boolean = false)

    /** Parses the stored file. */
    suspend fun load(id: String): CubeLut

    /** A small PNG preview of the LUT applied to a grey ramp and a hue sweep; rendered on first use and cached on disk. */
    suspend fun swatch(id: String): File

    companion object {
        const val ROOT = ""
    }
}

/** What [LutLibrary.snapshots] emits: every folder (parents included, root excluded) and every LUT. */
data class LutLibrarySnapshot(val folders: List<String>, val luts: List<LutEntry>) {
    fun lutsIn(folder: String): List<LutEntry> = luts.filter { it.folder == folder }

    /** Direct sub-folders of [folder]. */
    fun foldersIn(folder: String): List<String> = folders.filter { parentOf(it) == folder }

    companion object {
        fun parentOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = LutLibrary.ROOT)
    }
}

/** Metadata of one stored LUT; serialised in the library index. */
@Serializable
data class LutEntry(
    val id: String,
    val title: String,
    val folder: String,
    val format: LutFormatName,
    /** Lattice points per axis. */
    val size: Int,
    /** The file name it was imported under. */
    val fileName: String,
    val origin: LutOrigin,
    val sha256: String,
    val bytes: Long,
    val importedAtMillis: Long
) {
    val lutFormat: LutFormat get() = format.format
}

/** [LutFormat] as written in the index (`"cube"`, `"3dl"`), independent of the enum's names. */
@Serializable
enum class LutFormatName(val format: LutFormat) {
    @SerialName("cube")
    CUBE(LutFormat.CUBE),

    @SerialName("3dl")
    THREE_DL(LutFormat.THREE_DL);

    companion object {
        fun of(format: LutFormat): LutFormatName = entries.first { it.format == format }
    }
}

/** Where a LUT came from (REQ-COL-007 needs to tell catalogue downloads from user files). */
@Serializable
data class LutOrigin(
    val kind: Kind,
    /** For [Kind.IMPORTED] the display name of the picked file; for [Kind.CATALOGUE] the download URL. */
    val detail: String? = null,
    /** The `luts/catalog.json` entry id for [Kind.CATALOGUE]. */
    val catalogueId: String? = null
) {
    @Serializable
    enum class Kind {
        @SerialName("imported")
        IMPORTED,

        @SerialName("catalogue")
        CATALOGUE,

        @SerialName("generated")
        GENERATED,

        /** Rebuilt from the stored file after the index was lost; the original metadata is gone. */
        @SerialName("recovered")
        RECOVERED
    }

    companion object {
        fun imported(displayName: String) = LutOrigin(Kind.IMPORTED, displayName)

        fun catalogue(id: String, url: String) = LutOrigin(Kind.CATALOGUE, url, id)

        val generated = LutOrigin(Kind.GENERATED)
    }
}

data class ImportResult(val entry: LutEntry, val alreadyPresent: Boolean)

/** Something to import: a SAF document, a downloaded file, a generated LUT. */
interface LutSource {
    /** File name with extension; the extension selects the format. */
    val displayName: String

    /** Opens a fresh stream; the caller closes it. */
    fun open(): InputStream
}

class FileLutSource(private val file: File, override val displayName: String = file.name) : LutSource {
    override fun open(): InputStream = file.inputStream()
}

class BytesLutSource(override val displayName: String, private val bytes: ByteArray) : LutSource {
    override fun open(): InputStream = bytes.inputStream()
}

/** Every library failure, with a [reason] the UI maps to a localised message. */
class LutLibraryException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        UNSUPPORTED_FORMAT,
        INVALID_LUT,
        TOO_LARGE,
        NOT_FOUND,
        INVALID_NAME,
        FOLDER_NOT_EMPTY,
        FOLDER_EXISTS,

        /** The index was written by a newer app version; it is left untouched. */
        INDEX_TOO_NEW
    }
}
