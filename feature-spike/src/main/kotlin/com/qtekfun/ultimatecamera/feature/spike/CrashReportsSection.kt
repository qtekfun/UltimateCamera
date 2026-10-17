package com.qtekfun.ultimatecamera.feature.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.core.storage.crash.CrashReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics › Crash reports (D77): the newest crash the app recorded plus the system's record of recent abnormal exits
 * (native crashes, ANRs), to send to us through the share sheet or the clipboard. Read off the main thread.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashReportsSection(reports: CrashReports, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val shareTitle = stringResource(R.string.spike_crash_share)
    val text by produceState<String?>(null, reload) { value = withContext(Dispatchers.IO) { reports.shareText() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.spike_crash_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (text == null) R.string.spike_crash_none else R.string.spike_crash_available),
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val report = text
            FilledTonalButton(enabled = report != null, onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "UltimateCamera crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
            }) { Text(shareTitle) }
            OutlinedButton(enabled = report != null, onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("UltimateCamera crash report", report))
            }) { Text(stringResource(R.string.spike_crash_copy)) }
            OutlinedButton(enabled = report != null, onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { reports.clear() }
                    reload++
                }
            }) { Text(stringResource(R.string.spike_crash_clear)) }
        }
    }
}
