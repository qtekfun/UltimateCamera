package com.qtekfun.ultimatecamera.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.core.camera.CameraReportReader
import com.qtekfun.ultimatecamera.core.camera.CameraReportText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Camera diagnostics (D111): what every camera the app can see offers for video, as text to copy or share.
 * Read-only (no camera is opened) and without any device identifier; nothing is sent unless the user shares it.
 */
@Composable
internal fun CameraReportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.settings_camera_report_share)
    SettingsSection(stringResource(R.string.settings_camera_report_title), R.drawable.ic_set_features) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_camera_report_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(enabled = !reading, onClick = {
                reading = true
                scope.launch {
                    report = withContext(Dispatchers.IO) {
                        reportOrError { CameraReportText.format(CameraReportReader(context.getSystemService(CameraManager::class.java)).read()) }
                    }
                    reading = false
                }
            }) { Text(stringResource(R.string.settings_camera_report_generate)) }
            report?.let { text ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                    }) { Text(stringResource(R.string.settings_camera_report_copy)) }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }) { Text(shareTitle) }
                }
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

private const val CLIP_LABEL = "UltimateCamera camera report"

/** The report, or the failure as one line: a vendor HAL can throw anything while its cameras are read. */
internal fun reportOrError(read: () -> String): String = runCatching(read).getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
