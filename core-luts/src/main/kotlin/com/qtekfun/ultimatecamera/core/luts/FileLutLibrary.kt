package com.qtekfun.ultimatecamera.core.luts

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.CubeLutFormatException
import com.qtekfun.ultimatecamera.core.gpu.LutFiles
import com.qtekfun.ultimatecamera.core.gpu.LutFormat
import com.qtekfun.ultimatecamera.core.gpu.LutParseLimits
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LutLibrary] in a private directory (normally `filesDir/luts`):
 * ```
 * index.json          metadata of every LUT and folder (LutIndexStore: versioned schema, atomic replace)
 * files/<id>.<ext>    the imported bytes, unchanged (backups and re-parsing use these)
 * swatches/<id>.png   preview cache, regenerable, created on first use
 * tmp/                partial imports; emptied when the library opens
 * ```
 * Folders exist only in the index, so moving and renaming touch one small file and never the LUT files. Imports stream
 * through a temporary file (SHA-256 on the fly, [maxFileBytes] cap) and are parsed from disk, never held in memory as
 * text. A LUT file without an index entry (a crash between the file move and the index write) is adopted on open as
 * [LutOrigin.Kind.RECOVERED]; an unreadable index is set aside and rebuilt from the files the same way.
 */
class FileLutLibrary(
    private val root: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val limits: LutParseLimits = LutParseLimits(),
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : LutLibrary,
    LutBackupAccess {
    private val filesDir = File(root, "files")
    private val swatchDir = File(root, "swatches")
    private val tmpDir = File(root, "tmp")
    private val store = LutIndexStore(File(root, INDEX_FILE))
    private val mutex = Mutex()
    private val state = MutableStateFlow<LutIndex?>(null)

    override val snapshots: Flow<LutLibrarySnapshot> = flow {
        withContext(dispatcher) { mutex.withLock { loadedLocked() } }
        emitAll(state.filterNotNull().map { LutLibrarySnapshot(it.folders, it.luts) })
    }

    override suspend fun import(source: LutSource, folder: String, origin: LutOrigin, title: String?): ImportResult = withContext(dispatcher) {
        val target = LutPaths.checkFolder(folder, allowRoot = true)
        val format = LutFormat.fromFileName(source.displayName)
            ?: throw LutLibraryException(LutLibraryException.Reason.UNSUPPORTED_FORMAT, "not a .cube or .3dl file: ${source.displayName}")
        mutex.withLock { loadedLocked() } // Creates the directories.
        val tmp = File(tmpDir, "${newId()}.part")
        try {
            val (sha, bytes) = source.open().use { copyHashed(it, tmp) }
            val lut = parseFile(tmp, format)
            mutex.withLock {
                val index = loadedLocked()
                index.luts.firstOrNull { it.sha256 == sha }?.let { return@withContext ImportResult(it, alreadyPresent = true) }
                val id = newId()
                moveReplacing(tmp, fileOf(id, format))
                val entry = LutEntry(
                    id = id,
                    title = listOfNotNull(title, lut.title, source.displayName.substringBeforeLast('.'))
                        .map { it.trim().take(LutPaths.MAX_TITLE) }.firstOrNull { it.isNotEmpty() } ?: source.displayName,
                    folder = target,
                    format = LutFormatName.of(format),
                    size = lut.size,
                    fileName = source.displayName,
                    origin = origin,
                    sha256 = sha,
                    bytes = bytes,
                    importedAtMillis = clock()
                )
                commit(index.copy(folders = LutPaths.withParents(index.folders, target), luts = index.luts + entry))
                ImportResult(entry, alreadyPresent = false)
            }
        } finally {
            tmp.delete()
        }
    }

    override suspend fun rename(id: String, title: String): LutEntry = update(id) {
        val clean = title.trim()
        if (clean.isEmpty() || clean.length > LutPaths.MAX_TITLE || clean.any { c -> c.isISOControl() }) {
            throw LutLibraryException(LutLibraryException.Reason.INVALID_NAME, "invalid title")
        }
        it.copy(title = clean)
    }

    override suspend fun move(id: String, folder: String): LutEntry {
        val target = LutPaths.checkFolder(folder, allowRoot = true)
        return update(id, extraFolder = target) { it.copy(folder = target) }
    }

    override suspend fun delete(id: String): Unit = mutate { index ->
        val entry = index.find(id)
        val next = index.copy(luts = index.luts - entry)
        commit(next)
        deleteFiles(entry)
    }

    override suspend fun createFolder(path: String): Unit = mutate { index ->
        val folder = LutPaths.checkFolder(path, allowRoot = false)
        if (folder !in index.folders) commit(index.copy(folders = LutPaths.withParents(index.folders, folder)))
    }

    override suspend fun renameFolder(from: String, to: String): Unit = mutate { index ->
        val source = LutPaths.checkFolder(from, allowRoot = false)
        val target = LutPaths.checkFolder(to, allowRoot = false)
        if (source !in index.folders) throw LutLibraryException(LutLibraryException.Reason.NOT_FOUND, "no folder $source")
        if (target in index.folders) throw LutLibraryException(LutLibraryException.Reason.FOLDER_EXISTS, "folder $target exists")
        if (LutPaths.isInside(target, source)) throw LutLibraryException(LutLibraryException.Reason.INVALID_NAME, "cannot move $source into itself")
        val renamed = index.folders.map { LutPaths.rebase(it, source, target) }
        commit(
            index.copy(
                folders = LutPaths.withParents(renamed, target),
                luts = index.luts.map { it.copy(folder = LutPaths.rebase(it.folder, source, target)) }
            )
        )
    }

    override suspend fun deleteFolder(path: String, recursive: Boolean): Unit = mutate { index ->
        val folder = LutPaths.checkFolder(path, allowRoot = false)
        if (folder !in index.folders) throw LutLibraryException(LutLibraryException.Reason.NOT_FOUND, "no folder $folder")
        val removedFolders = index.folders.filter { LutPaths.isInside(it, folder) }
        val removedLuts = index.luts.filter { LutPaths.isInside(it.folder, folder) }
        if (!recursive && (removedFolders.size > 1 || removedLuts.isNotEmpty())) {
            throw LutLibraryException(LutLibraryException.Reason.FOLDER_NOT_EMPTY, "folder $folder is not empty")
        }
        commit(index.copy(folders = index.folders - removedFolders.toSet(), luts = index.luts - removedLuts.toSet()))
        removedLuts.forEach(::deleteFiles)
    }

    override suspend fun load(id: String): CubeLut = withContext(dispatcher) {
        val entry = mutex.withLock { loadedLocked().find(id) }
        val lut = parseFile(fileOf(entry.id, entry.lutFormat), entry.lutFormat)
        CubeLut(lut.size, lut.data, entry.title, lut.domainMin, lut.domainMax)
    }

    override suspend fun swatch(id: String): File = withContext(dispatcher) {
        val entry = mutex.withLock { loadedLocked().find(id) }
        val file = File(swatchDir, "${entry.id}.png")
        if (!file.isFile) {
            val lut = parseFile(fileOf(entry.id, entry.lutFormat), entry.lutFormat)
            val tmp = File(tmpDir, "${newId()}.png")
            try {
                tmp.outputStream().use { LutSwatch.write(lut, it) }
                moveReplacing(tmp, file)
            } finally {
                tmp.delete()
            }
        }
        file
    }

    override suspend fun backupView(): LutBackupView = withContext(dispatcher) {
        val index = mutex.withLock { loadedLocked() }
        LutBackupView(index.folders, index.luts) { entry -> fileOf(entry.id, entry.lutFormat).inputStream() }
    }

    override suspend fun restore(folders: List<String>, luts: List<StagedLut>, replace: Boolean): Map<String, String> = withContext(dispatcher) {
        // Everything is checked before the library changes: a bad backup leaves it as it was.
        folders.forEach { LutPaths.checkFolder(it, allowRoot = false) }
        luts.forEach { staged ->
            LutPaths.checkFolder(staged.entry.folder, allowRoot = true)
            val sha = staged.file.inputStream().use(::sha256Of)
            if (sha != staged.entry.sha256) throw LutLibraryException(LutLibraryException.Reason.INVALID_LUT, "checksum mismatch for ${staged.entry.id}")
            parseFile(staged.file, staged.entry.lutFormat)
        }
        mutex.withLock {
            val current = loadedLocked()
            var next = if (replace) LutIndex() else current
            next = next.copy(folders = (next.folders + folders).distinct())
            val ids = mutableMapOf<String, String>()
            for (staged in luts) {
                val present = next.luts.firstOrNull { it.sha256 == staged.entry.sha256 }
                val id = present?.id ?: staged.entry.id.takeIf { id -> LutIndexCodec.isSafeId(id) && next.luts.none { it.id == id } } ?: newId()
                ids[staged.entry.id] = id
                if (present != null) continue
                val entry = staged.entry.copy(id = id)
                val tmp = File(tmpDir, "${newId()}.part")
                staged.file.copyTo(tmp, overwrite = true)
                moveReplacing(tmp, fileOf(id, entry.lutFormat))
                File(swatchDir, "$id.png").delete()
                next = next.copy(folders = LutPaths.withParents(next.folders, entry.folder), luts = next.luts + entry)
            }
            commit(next)
            if (replace) current.luts.filter { old -> next.luts.none { it.id == old.id && it.format == old.format } }.forEach(::deleteFiles)
            ids
        }
    }

    // Internals. Everything below that touches the index runs with the mutex held.

    private suspend fun update(id: String, extraFolder: String? = null, change: (LutEntry) -> LutEntry): LutEntry {
        var result: LutEntry? = null
        mutate { index ->
            val old = index.find(id)
            val new = change(old)
            val folders = if (extraFolder != null) LutPaths.withParents(index.folders, extraFolder) else index.folders
            commit(index.copy(folders = folders, luts = index.luts.map { if (it.id == id) new else it }))
            result = new
        }
        return result!!
    }

    private suspend fun mutate(block: (LutIndex) -> Unit) = withContext(dispatcher) {
        mutex.withLock { block(loadedLocked()) }
    }

    private fun commit(index: LutIndex) {
        val sorted = index.copy(folders = index.folders.distinct().sorted())
        store.write(sorted)
        state.value = sorted
    }

    private fun loadedLocked(): LutIndex {
        state.value?.let { return it }
        for (dir in listOf(filesDir, swatchDir, tmpDir)) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        }
        tmpDir.listFiles()?.forEach { it.delete() }
        val read = store.read()
        var index = read.index
        var changed = read.recovered
        // Entries whose file vanished cannot be used.
        val present = index.luts.filter { fileOf(it.id, it.lutFormat).isFile }
        if (present.size != index.luts.size) {
            index = index.copy(luts = present)
            changed = true
        }
        // Files without an entry are adopted (or removed when they no longer parse).
        val known = index.luts.map { "${it.id}.${it.lutFormat.extension}" }.toSet()
        val adopted = filesDir.listFiles().orEmpty().filter { it.isFile && it.name !in known }.mapNotNull(::adopt)
        if (adopted.isNotEmpty()) {
            index = index.copy(luts = index.luts + adopted)
            changed = true
        }
        val folders = index.luts.fold(index.folders) { acc, lut -> LutPaths.withParents(acc, lut.folder) }
        if (folders != index.folders) {
            index = index.copy(folders = folders)
            changed = true
        }
        if (changed) commit(index) else state.value = index
        return state.value!!
    }

    private fun adopt(file: File): LutEntry? {
        val format = LutFormat.fromFileName(file.name)
        val id = file.name.substringBeforeLast('.')
        val lut = format?.let { runCatching { parseFile(file, it) }.getOrNull() }
        if (format == null || lut == null) {
            file.delete()
            return null
        }
        val sha = file.inputStream().use { sha256Of(it) }
        return LutEntry(
            id = id,
            title = lut.title?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            folder = LutLibrary.ROOT,
            format = LutFormatName.of(format),
            size = lut.size,
            fileName = file.name,
            origin = LutOrigin(LutOrigin.Kind.RECOVERED),
            sha256 = sha,
            bytes = file.length(),
            importedAtMillis = file.lastModified()
        )
    }

    private fun parseFile(file: File, format: LutFormat): CubeLut = try {
        file.bufferedReader(Charsets.UTF_8).use { LutFiles.parse(format, it, limits) }
    } catch (e: CubeLutFormatException) {
        throw LutLibraryException(LutLibraryException.Reason.INVALID_LUT, e.message ?: "invalid LUT", e)
    }

    /** Streams [input] into [target], hashing as it goes; fails past [maxFileBytes] without reading further. */
    private fun copyHashed(input: InputStream, target: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxFileBytes) throw LutLibraryException(LutLibraryException.Reason.TOO_LARGE, "larger than $maxFileBytes bytes")
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
        return digest.digest().toHex() to total
    }

    private fun deleteFiles(entry: LutEntry) {
        fileOf(entry.id, entry.lutFormat).delete()
        File(swatchDir, "${entry.id}.png").delete()
    }

    private fun fileOf(id: String, format: LutFormat) = File(filesDir, "$id.${format.extension}")

    private fun LutIndex.find(id: String): LutEntry =
        luts.firstOrNull { it.id == id } ?: throw LutLibraryException(LutLibraryException.Reason.NOT_FOUND, "no LUT $id")

    companion object {
        const val INDEX_FILE = "index.json"

        /** The makers' 65-point LUTs are 5–8 MB of text; 64 MiB leaves room without letting a wrong pick fill the disk. */
        const val DEFAULT_MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val BUFFER = 64 * 1024
    }
}

internal fun sha256Of(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun moveReplacing(from: File, to: File) {
    java.nio.file.Files.move(
        from.toPath(),
        to.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE
    )
}
