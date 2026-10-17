package com.qtekfun.ultimatecamera.feature.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.luts.ContentUriLutSource
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutLibraries
import com.qtekfun.ultimatecamera.core.luts.LutLibrary
import com.qtekfun.ultimatecamera.core.luts.LutLibraryException
import com.qtekfun.ultimatecamera.core.luts.LutLibrarySnapshot
import com.qtekfun.ultimatecamera.core.luts.LutOrigin
import com.qtekfun.ultimatecamera.core.luts.LutSource
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueException
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueLut
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueLutDownloader
import com.qtekfun.ultimatecamera.core.luts.catalogue.DownloadException
import com.qtekfun.ultimatecamera.core.luts.catalogue.DownloadPrompt
import com.qtekfun.ultimatecamera.core.luts.catalogue.LutCatalogue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A one-shot note for the library screen. */
sealed interface LibraryMessage {
    data class Imported(val title: String, val alreadyPresent: Boolean) : LibraryMessage

    data class Failed(val problem: LibraryProblem) : LibraryMessage
}

/** Failures the screen explains in the user's language (the technical detail stays in English, D15). */
enum class LibraryProblem {
    UNSUPPORTED_FORMAT,
    INVALID_LUT,
    TOO_LARGE,
    INVALID_NAME,
    FOLDER_NOT_EMPTY,
    FOLDER_EXISTS,
    NOT_FOUND,
    NETWORK,
    CHECKSUM,
    STORAGE;

    companion object {
        fun of(e: IOException): LibraryProblem = when (e) {
            is LutLibraryException -> when (e.reason) {
                LutLibraryException.Reason.UNSUPPORTED_FORMAT -> UNSUPPORTED_FORMAT
                LutLibraryException.Reason.INVALID_LUT -> INVALID_LUT
                LutLibraryException.Reason.TOO_LARGE -> TOO_LARGE
                LutLibraryException.Reason.NOT_FOUND -> NOT_FOUND
                LutLibraryException.Reason.INVALID_NAME -> INVALID_NAME
                LutLibraryException.Reason.FOLDER_NOT_EMPTY -> FOLDER_NOT_EMPTY
                LutLibraryException.Reason.FOLDER_EXISTS -> FOLDER_EXISTS
                LutLibraryException.Reason.INDEX_TOO_NEW -> STORAGE
            }
            is DownloadException -> when (e.reason) {
                DownloadException.Reason.CHECKSUM_MISMATCH, DownloadException.Reason.ENTRY_MISSING -> CHECKSUM
                DownloadException.Reason.TOO_LARGE -> TOO_LARGE
                else -> NETWORK
            }
            else -> STORAGE
        }
    }
}

data class LibraryUiState(
    val folder: String = LutLibrary.ROOT,
    val busy: Boolean = false,
    /** Download progress 0..1 while a catalogue LUT is fetched. */
    val progress: Float? = null,
    val message: LibraryMessage? = null
)

/**
 * The LUT library screen (REQ-COL-003, REQ-COL-006/007): browse folders, import through SAF, rename, delete, and fetch
 * the makers' LUTs from their official URL after the user confirmed (https only, SHA-256 checked by core-luts).
 */
class LutLibraryViewModel internal constructor(
    private val library: LutLibrary,
    private val downloader: CatalogueLutDownloader,
    loadCatalogue: () -> LutCatalogue,
    /** A picked document as a LUT source (SAF in the app). */
    private val sourceOf: (Uri) -> LutSource
) : ViewModel() {
    constructor(application: Application) : this(
        LutLibraries.library(application),
        LutLibraries.downloader(application),
        { LutLibraries.catalogue(application) },
        { uri -> ContentUriLutSource(application.contentResolver, uri) }
    )

    private var download: Job? = null

    /** The makers' catalogue; empty if the shipped copy cannot be read (a build problem, caught by the tests). */
    @Suppress("SwallowedException")
    val catalogue: List<CatalogueLut> = try {
        loadCatalogue().luts
    } catch (e: CatalogueException) {
        emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    val snapshot: StateFlow<LutLibrarySnapshot?> = library.snapshots
        .catch { emit(LutLibrarySnapshot(emptyList(), emptyList())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    fun open(folder: String) = _state.update { it.copy(folder = folder) }

    fun up() = _state.update { it.copy(folder = LutLibrarySnapshot.parentOf(it.folder)) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun import(uris: List<Uri>) = importSources(uris.map(sourceOf))

    /** Imports [sources] into the open folder, one after the other; stops at the first failure and says why. */
    internal fun importSources(sources: List<LutSource>) = change {
        val folder = _state.value.folder
        var last: LibraryMessage? = null
        for (source in sources) {
            last = try {
                val result = library.import(source, folder)
                LibraryMessage.Imported(result.entry.title, result.alreadyPresent)
            } catch (e: IOException) {
                LibraryMessage.Failed(LibraryProblem.of(e))
            }
            if (last is LibraryMessage.Failed) break
        }
        last
    }

    fun createFolder(name: String) = change {
        library.createFolder(child(_state.value.folder, name.trim()))
        null
    }

    fun renameFolder(path: String, name: String) = change {
        library.renameFolder(path, child(LutLibrarySnapshot.parentOf(path), name.trim()))
        null
    }

    fun deleteFolder(path: String) = change {
        library.deleteFolder(path, recursive = true)
        null
    }

    fun rename(entry: LutEntry, title: String) = change {
        library.rename(entry.id, title)
        null
    }

    fun delete(entry: LutEntry) = change {
        library.delete(entry.id)
        null
    }

    fun describe(lut: CatalogueLut): DownloadPrompt = downloader.describe(lut)

    /** Fetches [lut] from its maker; only called from the confirmation dialog's download button (REQ-COL-007). */
    fun download(lut: CatalogueLut) {
        if (download?.isActive == true) return
        download = viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f) }
            val message = try {
                val result = downloader.download(lut, userConfirmed = true) { done, total ->
                    _state.update { it.copy(progress = if (total > 0) done.toFloat() / total else null) }
                }
                LibraryMessage.Imported(result.entry.title, result.alreadyPresent)
            } catch (e: IOException) {
                LibraryMessage.Failed(LibraryProblem.of(e))
            }
            _state.update { it.copy(busy = false, progress = null, message = message) }
        }
    }

    @Suppress("SwallowedException") // No swatch is a blank square.
    suspend fun swatch(id: String): File? = try {
        library.swatch(id)
    } catch (e: IOException) {
        null
    }

    /** Runs a library change, showing [block]'s message or its failure. */
    private fun change(block: suspend () -> LibraryMessage?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = try {
                block()
            } catch (e: IOException) {
                LibraryMessage.Failed(LibraryProblem.of(e))
            }
            _state.update { it.copy(busy = false, message = message ?: it.message) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The path of folder [name] inside [parent]. */
internal fun child(parent: String, name: String): String = if (parent == LutLibrary.ROOT) name else "$parent/$name"

/** `["", "A", "A/B"]` for `"A/B"`: the folders from the root down to [folder], for the breadcrumb. */
internal fun breadcrumb(folder: String): List<String> {
    if (folder == LutLibrary.ROOT) return listOf(LutLibrary.ROOT)
    val parts = folder.split('/')
    return listOf(LutLibrary.ROOT) + parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}

/** Catalogue ids already downloaded into the library. */
internal fun downloadedCatalogueIds(luts: List<LutEntry>): Set<String> =
    luts.filter { it.origin.kind == LutOrigin.Kind.CATALOGUE }.mapNotNullTo(HashSet()) { it.origin.catalogueId }
