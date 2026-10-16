package com.qtekfun.ultimatecamera.core.luts

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The library index as stored in `index.json`. */
@Serializable
internal data class LutIndex(
    val schemaVersion: Int = LutIndexStore.SCHEMA_VERSION,
    val folders: List<String> = emptyList(),
    val luts: List<LutEntry> = emptyList()
)

/**
 * Reads and writes the index. Writes go to `index.json.tmp`, are synced to disk and then renamed over `index.json`
 * (atomic on the same file system), so a crash leaves either the old or the new index, never a torn one.
 *
 * Schema: `schemaVersion` is bumped on any incompatible change and older versions are migrated here. A newer version is
 * refused ([LutLibraryException.Reason.INDEX_TOO_NEW]) instead of being overwritten, so a downgrade cannot lose data. An
 * unreadable file is renamed to `index.json.corrupt` and reported as [Read.recovered] with an empty index; the library
 * then adopts the LUT files it finds.
 */
internal class LutIndexStore(private val file: File) {
    data class Read(val index: LutIndex, val recovered: Boolean)

    fun read(): Read {
        if (!file.isFile) return Read(LutIndex(), recovered = false)
        val text = file.readText()
        // Unparseable JSON has no version: it is handled as corrupt below.
        val version = parsing { json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int }.getOrNull()
        if (version != null && version > SCHEMA_VERSION) {
            throw LutLibraryException(LutLibraryException.Reason.INDEX_TOO_NEW, "index schema $version is newer than $SCHEMA_VERSION")
        }
        val index = parsing { json.decodeFromString(LutIndex.serializer(), text) }.getOrNull()
        if (index != null) return Read(index, recovered = false)
        setAside()
        return Read(LutIndex(), recovered = true)
    }

    /** Runs [block], turning only JSON/shape errors into a failed result (I/O errors and the rest propagate). */
    private fun <T> parsing(block: () -> T): Result<T> = runCatching(block).onFailure {
        if (it !is SerializationException && it !is IllegalArgumentException) throw it
    }

    fun write(index: LutIndex) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(LutIndex.serializer(), index.copy(schemaVersion = SCHEMA_VERSION)).toByteArray())
            out.fd.sync()
        }
        moveReplacing(tmp, file)
    }

    private fun setAside() = moveReplacing(file, File(file.parentFile, "${file.name}.corrupt"))

    companion object {
        const val SCHEMA_VERSION = 1

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Folder path rules shared by the library operations. */
internal object LutPaths {
    const val MAX_TITLE = 200
    private const val MAX_SEGMENT = 64
    private const val MAX_DEPTH = 8

    fun checkFolder(path: String, allowRoot: Boolean): String {
        if (path == LutLibrary.ROOT) {
            if (allowRoot) return path
            throw invalid("the root folder cannot be used here")
        }
        val segments = path.split('/')
        if (segments.size > MAX_DEPTH) throw invalid("folders nest at most $MAX_DEPTH deep")
        for (s in segments) {
            val bad = s.isEmpty() || s != s.trim() || s == "." || s == ".." || s.length > MAX_SEGMENT || s.any { it.isISOControl() || it == '\\' }
            if (bad) throw invalid("invalid folder name '$s'")
        }
        return path
    }

    /** [folders] plus [path] and all its parents (root excluded). */
    fun withParents(folders: List<String>, path: String): List<String> {
        if (path == LutLibrary.ROOT) return folders
        val result = folders.toMutableList()
        var current = path
        while (current != LutLibrary.ROOT) {
            if (current !in result) result += current
            current = LutLibrarySnapshot.parentOf(current)
        }
        return result.sorted()
    }

    fun isInside(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")

    fun rebase(path: String, from: String, to: String): String = when {
        path == from -> to
        path.startsWith("$from/") -> to + path.substring(from.length)
        else -> path
    }

    private fun invalid(message: String) = LutLibraryException(LutLibraryException.Reason.INVALID_NAME, message)
}
