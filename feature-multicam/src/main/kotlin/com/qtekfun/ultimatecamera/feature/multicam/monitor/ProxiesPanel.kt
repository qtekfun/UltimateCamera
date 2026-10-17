package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraPanel
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.multicam.R
import java.util.Locale

/** The "Proxies" list of one camera (REQ-NET-006, D59): refreshes when opened; a tap downloads or resumes a proxy. */
@Composable
fun ProxiesPanel(downloads: ProxyDownloads, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ui by downloads.state.collectAsStateWithLifecycle()
    LaunchedEffect(downloads) { downloads.refresh(scope) }
    CameraPanel(stringResource(R.string.mc_proxies_title), onClose, modifier.widthIn(max = PANEL_MAX_WIDTH.dp)) {
        Column(Modifier.heightIn(max = LIST_MAX_HEIGHT.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                ui.loading -> Muted(stringResource(R.string.mc_proxies_loading))
                ui.unreachable -> Muted(stringResource(R.string.mc_proxies_unreachable))
                ui.items.isEmpty() -> Muted(stringResource(R.string.mc_proxies_empty))
            }
            ui.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = CameraTheme.type.label, color = CameraTheme.colors.content)
                        Muted(
                            stringResource(stateText(item.state)) + " · " +
                                stringResource(R.string.mc_proxies_size, megabytes(item.held), megabytes(item.bytes))
                        )
                    }
                    if (item.state != ProxyItemState.DONE && item.state != ProxyItemState.DOWNLOADING) {
                        OptionChip(stringResource(R.string.mc_proxies_download), selected = false, onClick = { downloads.download(scope, item.name) })
                    }
                }
            }
            OptionChip(stringResource(R.string.mc_proxies_refresh), selected = false, onClick = { downloads.refresh(scope) })
        }
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = CameraTheme.type.label, color = CameraTheme.colors.contentMuted)

private fun stateText(state: ProxyItemState): Int = when (state) {
    ProxyItemState.REMOTE -> R.string.mc_proxies_state_remote
    ProxyItemState.PARTIAL -> R.string.mc_proxies_state_partial
    ProxyItemState.DOWNLOADING -> R.string.mc_proxies_state_downloading
    ProxyItemState.PAUSED -> R.string.mc_proxies_state_paused
    ProxyItemState.DONE -> R.string.mc_proxies_state_done
    ProxyItemState.CORRUPT -> R.string.mc_proxies_state_corrupt
    ProxyItemState.FAILED -> R.string.mc_proxies_state_failed
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MB)

private const val BYTES_PER_MB = 1_000_000.0
private const val PANEL_MAX_WIDTH = 420
private const val LIST_MAX_HEIGHT = 360
