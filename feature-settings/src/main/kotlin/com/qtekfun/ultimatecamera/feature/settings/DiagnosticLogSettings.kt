package com.qtekfun.ultimatecamera.feature.settings

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.qtekfun.ultimatecamera.core.camera.DiagnosticLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Diagnostic log (D113): off by default; while on, the camera paths write what they see and decide to a file
 * in the phone, to share later for analysis. Costs some performance, so the description says so.
 */
@Composable
internal fun DiagnosticLogSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(DiagnosticLog.enabled) }
    var bytes by remember { mutableLongStateOf(DiagnosticLog.size()) }
    val shareTitle = stringResource(R.string.settings_diagnostic_log_share)
    SettingsSection(stringResource(R.string.settings_diagnostic_log_title), R.drawable.ic_set_features) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(stringResource(R.string.settings_diagnostic_log_switch), stringResource(R.string.settings_diagnostic_log_description), on) {
                on = it
                bytes = toggleLog(it)
            }
            Text(
                stringResource(R.string.settings_diagnostic_log_size, Formatter.formatShortFileSize(context, bytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = bytes > 0, onClick = {
                    scope.launch {
                        val copy = withContext(Dispatchers.IO) { shareCopy(context.cacheDir, DiagnosticLog.text()) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", copy)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }
                }) { Text(shareTitle) }
                OutlinedButton(enabled = bytes > 0, onClick = { bytes = clearLog() }) { Text(stringResource(R.string.settings_diagnostic_log_clear)) }
            }
        }
    }
}

/** Turns the log on or off and returns its size. */
internal fun toggleLog(on: Boolean): Long {
    DiagnosticLog.setEnabled(on)
    return DiagnosticLog.size()
}

/** Deletes the log and returns its size (0 unless a line came in meanwhile). */
internal fun clearLog(): Long {
    DiagnosticLog.clear()
    return DiagnosticLog.size()
}

/** Authority of the provider in this module's manifest, after the application id. */
private const val AUTHORITY_SUFFIX = ".diagnostics"

/** Writes [text] to the shared copy under [cacheDir] (the only folder the provider exposes) and returns it. */
internal fun shareCopy(cacheDir: File, text: String): File {
    val dir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
    return File(dir, SHARE_FILE).apply { writeText(text) }
}

internal const val SHARE_DIR = "diagnostics"
internal const val SHARE_FILE = "ultimatecamera-diagnostic.log"
