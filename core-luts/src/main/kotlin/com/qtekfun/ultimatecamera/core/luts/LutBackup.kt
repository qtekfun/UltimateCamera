package com.qtekfun.ultimatecamera.core.luts

import java.io.File
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the backup (REQ-BAK-002, D65) needs from the library, kept apart from [LutLibrary] so its fakes need not change.
 * Implemented by [FileLutLibrary].
 */
interface LutBackupAccess {
    /** The library as it is now. Files are read with [LutBackupView.open], one at a time, never loaded whole. */
    suspend fun backupView(): LutBackupView

    /**
     * Adds [luts] (and [folders]) to the library, or with [replace] makes them the whole library. Every staged file must
     * match its entry's SHA-256 and parse, or nothing changes. A LUT already present (same SHA-256) is not copied twice;
     * an id taken by another LUT gets a new one. Returns backup id → library id, for settings that name a LUT.
     */
    suspend fun restore(folders: List<String>, luts: List<StagedLut>, replace: Boolean): Map<String, String>
}

/** A snapshot of the library for an export: its folders and LUTs, and [open] to stream one stored file. */
class LutBackupView(val folders: List<String>, val luts: List<LutEntry>, private val opener: (LutEntry) -> InputStream) {
    fun open(entry: LutEntry): InputStream = opener(entry)

    /** The index as stored in the library's `index.json`. */
    fun indexJson(): String = LutIndexCodec.encode(folders, luts)
}

/** A LUT read from a backup into [file] (a staging file on disk), with the metadata it had in the backup's index. */
data class StagedLut(val entry: LutEntry, val file: File)

/** Encodes and decodes the library index in the backup with the library's own schema rules. */
object LutIndexCodec {
    const val SCHEMA_VERSION = LutIndexStore.SCHEMA_VERSION

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(folders: List<String>, luts: List<LutEntry>): String = json.encodeToString(LutIndex.serializer(), LutIndex(SCHEMA_VERSION, folders, luts))

    /**
     * The folders and LUTs of an index. A newer schema throws [LutLibraryException.Reason.INDEX_TOO_NEW]; anything
     * unreadable throws [LutLibraryException.Reason.INVALID_LUT].
     */
    fun decode(text: String): Pair<List<String>, List<LutEntry>> {
        val index = try {
            json.decodeFromString(LutIndex.serializer(), text)
        } catch (e: SerializationException) {
            throw LutLibraryException(LutLibraryException.Reason.INVALID_LUT, "unreadable LUT index", e)
        } catch (e: IllegalArgumentException) {
            throw LutLibraryException(LutLibraryException.Reason.INVALID_LUT, "unreadable LUT index", e)
        }
        if (index.schemaVersion > SCHEMA_VERSION) {
            throw LutLibraryException(LutLibraryException.Reason.INDEX_TOO_NEW, "index schema ${index.schemaVersion} is newer than $SCHEMA_VERSION")
        }
        return index.folders to index.luts
    }

    /** Ids become file names: only letters, digits, `-` and `_` (a crafted backup cannot name `../x`). */
    fun isSafeId(id: String): Boolean = id.length in 1..MAX_ID && id.all { (it.isLetterOrDigit() && it.code < ASCII_END) || it == '-' || it == '_' }

    private const val MAX_ID = 64
    private const val ASCII_END = 128
}
