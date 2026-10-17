package com.qtekfun.ultimatecamera.feature.settings

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.backup.BackupDevice
import com.qtekfun.ultimatecamera.core.backup.BackupException
import com.qtekfun.ultimatecamera.core.backup.BackupOrigin
import com.qtekfun.ultimatecamera.core.backup.BackupReader
import com.qtekfun.ultimatecamera.core.backup.BackupSources
import com.qtekfun.ultimatecamera.core.backup.BackupSummary
import com.qtekfun.ultimatecamera.core.backup.BackupWriter
import com.qtekfun.ultimatecamera.core.backup.RestoreReport
import com.qtekfun.ultimatecamera.core.backup.RestoreTargets
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.luts.LutBackupAccess
import com.qtekfun.ultimatecamera.core.luts.LutLibraries
import com.qtekfun.ultimatecamera.core.settings.DataStoreSettingsRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsBackupAccess
import com.qtekfun.ultimatecamera.core.settings.backup
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Failures the backup screen explains in the user's language. */
enum class BackupProblem {
    NOT_A_BACKUP,
    WRONG_PASSWORD,
    CORRUPT,
    TOO_NEW,
    STORAGE;

    companion object {
        fun of(e: IOException): BackupProblem = when ((e as? BackupException)?.reason) {
            BackupException.Reason.NOT_A_BACKUP -> NOT_A_BACKUP
            BackupException.Reason.WRONG_PASSWORD, BackupException.Reason.PASSWORD_REQUIRED -> WRONG_PASSWORD
            BackupException.Reason.CORRUPT -> CORRUPT
            BackupException.Reason.TOO_NEW -> TOO_NEW
            null -> STORAGE
        }
    }
}

sealed interface BackupUiState {
    data object Idle : BackupUiState

    data object Working : BackupUiState

    /** The picked backup is encrypted; [wrong] after a password that did not open it. */
    data class NeedPassword(val uri: Uri, val wrong: Boolean) : BackupUiState

    /** Validated: the summary to confirm, with merge or replace. */
    data class Review(val uri: Uri, val summary: BackupSummary) : BackupUiState

    data object Exported : BackupUiState

    data class Restored(val report: RestoreReport) : BackupUiState

    data class Failed(val problem: BackupProblem) : BackupUiState
}

/**
 * Settings › Backup (REQ-BAK-001/002, D65): export to one SAF document, import with a summary first. All file work runs
 * on the IO dispatcher and streams; the password lives only in this view model until the operation ends.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var importPassword: CharArray? = null

    private val app get() = getApplication<Application>()
    private val settings: SettingsBackupAccess? get() = DataStoreSettingsRepository.getInstance(app).backup
    private val luts: LutBackupAccess? get() = LutLibraries.library(app) as? LutBackupAccess

    /** Writes the backup to [uri] (from ACTION_CREATE_DOCUMENT). A failed export deletes the partial document. */
    fun export(uri: Uri, password: CharArray?, includeCapabilities: Boolean) {
        val settings = settings ?: return
        val luts = luts ?: return
        _state.value = BackupUiState.Working
        viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("$uri")
                    val capabilities = AndroidCapabilities.store(app).file.takeIf { includeCapabilities }
                    BackupWriter.write(out, BackupSources(settings, luts, capabilities), origin(), password?.takeIf { it.isNotEmpty() })
                }
                BackupUiState.Exported
            } catch (e: IOException) {
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                BackupUiState.Failed(BackupProblem.of(e))
            } finally {
                password?.fill(' ')
            }
        }
    }

    /** Validates the picked backup and shows its summary, or asks for its password. */
    fun inspect(uri: Uri, password: CharArray? = null) {
        _state.value = BackupUiState.Working
        forgetPassword()
        importPassword = password
        viewModelScope.launch {
            _state.value = try {
                val summary = withContext(Dispatchers.IO) { BackupReader().inspect({ open(uri) }, password, AndroidCapabilities.identity()) }
                BackupUiState.Review(uri, summary)
            } catch (e: BackupException) {
                when (e.reason) {
                    BackupException.Reason.PASSWORD_REQUIRED -> BackupUiState.NeedPassword(uri, wrong = false)
                    BackupException.Reason.WRONG_PASSWORD -> BackupUiState.NeedPassword(uri, wrong = true)
                    else -> BackupUiState.Failed(BackupProblem.of(e))
                }
            } catch (e: IOException) {
                BackupUiState.Failed(BackupProblem.of(e))
            }
        }
    }

    /** Imports the reviewed backup, merged into what is here or replacing it. */
    fun restore(uri: Uri, replace: Boolean) {
        val settings = settings ?: return
        val luts = luts ?: return
        _state.value = BackupUiState.Working
        viewModelScope.launch {
            _state.value = try {
                val report = withContext(Dispatchers.IO) {
                    val targets = RestoreTargets(settings, luts, AndroidCapabilities.store(app), AndroidCapabilities.identity())
                    BackupReader().restore({ open(uri) }, importPassword, replace, File(app.filesDir, STAGING_DIR), targets)
                }
                BackupUiState.Restored(report)
            } catch (e: IOException) {
                BackupUiState.Failed(BackupProblem.of(e))
            } finally {
                forgetPassword()
            }
        }
    }

    /** Back to idle; forgets any password. */
    fun dismiss() {
        forgetPassword()
        _state.value = BackupUiState.Idle
    }

    override fun onCleared() = forgetPassword()

    private fun forgetPassword() {
        importPassword?.fill(' ')
        importPassword = null
    }

    private fun open(uri: Uri): InputStream = app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("$uri")

    private fun origin(): BackupOrigin {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val identity = AndroidCapabilities.identity()
        return BackupOrigin(
            appVersion = info.versionName.orEmpty(),
            appVersionCode = info.longVersionCode,
            device = BackupDevice(identity.manufacturer, identity.model, identity.fingerprint),
            createdAt = Instant.now().toString()
        )
    }

    companion object {
        private const val STAGING_DIR = "backup-staging"

        /** The suggested file name, e.g. `UltimateCamera-backup-2026-10-17.zip`. */
        fun suggestedName(date: java.time.LocalDate = java.time.LocalDate.now()): String = "UltimateCamera-backup-$date.zip"
    }
}
