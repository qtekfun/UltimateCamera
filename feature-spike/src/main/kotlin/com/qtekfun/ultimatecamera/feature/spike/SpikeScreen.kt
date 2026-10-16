package com.qtekfun.ultimatecamera.feature.spike

import android.Manifest
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.net.NetworkLink
import kotlinx.coroutines.delay

/** What the adb automation entry point asked for (see scripts/spike/run-spike.sh). */
data class AutorunRequest(val tests: List<SpikeTest>, val seconds: Int, val sustainedSeconds: Int, val netHost: String?, val netLink: NetworkLink? = null)

private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)

@Composable
fun SpikeScreen(viewModel: SpikeViewModel, autorun: AutorunRequest?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(permissions.take(2).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermissions = permissions.take(2).all { result[it] == true || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importLut) }

    LaunchedEffect(Unit) { if (!hasPermissions) permissionLauncher.launch(permissions) }
    LaunchedEffect(autorun, hasPermissions) {
        if (autorun != null && hasPermissions) {
            // Give the preview surface a moment to exist so pipeline runs include the monitor load.
            repeat(30) { if (viewModel.previewSurface == null) delay(100) }
            viewModel.run(autorun.tests, autorun.seconds, autorun.sustainedSeconds, autorun.netHost, autorun.netLink, export = true)
        }
    }
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    val window = LocalActivity.current?.window
    DisposableEffect(state.maxBrightness, window) {
        fun apply(level: Float) {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level } }
        }
        apply(if (state.maxBrightness) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    Scaffold { padding ->
        Row(Modifier.fillMaxSize().padding(padding).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { PreviewSurface(viewModel) }
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.status, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                state.lastExport?.let { Text(stringResource(R.string.spike_exported, it), style = MaterialTheme.typography.bodySmall) }
                if (!hasPermissions) Text(stringResource(R.string.spike_permissions), color = MaterialTheme.colorScheme.error)
            }
            Controls(state, viewModel, Modifier.weight(1f), onImportLut = { lutPicker.launch(arrayOf("*/*")) })
        }
    }
}

@Composable
private fun PreviewSurface(viewModel: SpikeViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        viewModel.previewSurface = holder.surface
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        viewModel.previewSurface = null
                    }
                })
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(state: SpikeUiState, viewModel: SpikeViewModel, modifier: Modifier, onImportLut: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf(NetworkLink.WIFI_ROUTER) }
    val device = state.report.device
    val idle = !state.running
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.spike_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.spike_device, device.manufacturer, device.model, device.socModel, device.android),
                style = MaterialTheme.typography.bodySmall
            )
            Text(stringResource(R.string.spike_lut, state.lutName), style = MaterialTheme.typography.bodySmall)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = idle, onClick = {
                    viewModel.run(listOf(SpikeTest.CAPABILITIES, SpikeTest.MATRIX, SpikeTest.CONCURRENT, SpikeTest.AUDIO), export = true)
                }) { Text(stringResource(R.string.spike_run_all)) }
                FilledTonalButton(enabled = idle, onClick = { viewModel.run(listOf(SpikeTest.CAPABILITIES)) }) { Text(stringResource(R.string.spike_run_caps)) }
                FilledTonalButton(enabled = idle, onClick = { viewModel.run(listOf(SpikeTest.MATRIX)) }) { Text(stringResource(R.string.spike_run_matrix)) }
                FilledTonalButton(enabled = idle, onClick = {
                    viewModel.run(listOf(SpikeTest.SUSTAINED))
                }) { Text(stringResource(R.string.spike_run_sustained)) }
                FilledTonalButton(enabled = idle, onClick = {
                    viewModel.run(listOf(SpikeTest.CONCURRENT))
                }) { Text(stringResource(R.string.spike_run_concurrent)) }
                FilledTonalButton(enabled = idle, onClick = { viewModel.run(listOf(SpikeTest.AUDIO)) }) { Text(stringResource(R.string.spike_run_audio)) }
                OutlinedButton(enabled = idle, onClick = onImportLut) { Text(stringResource(R.string.spike_import_lut)) }
                OutlinedButton(enabled = idle, onClick = viewModel::export) { Text(stringResource(R.string.spike_export)) }
                OutlinedButton(enabled = idle, onClick = viewModel::reset) { Text(stringResource(R.string.spike_reset)) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = state.maxBrightness, onCheckedChange = viewModel::setMaxBrightness)
                Column {
                    Text(stringResource(R.string.spike_max_brightness), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.spike_max_brightness_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
            Text(stringResource(R.string.spike_network), style = MaterialTheme.typography.titleMedium)
            state.localAddresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = link == NetworkLink.WIFI_ROUTER, onClick = {
                    link = NetworkLink.WIFI_ROUTER
                }, label = { Text(stringResource(R.string.spike_link_wifi)) })
                FilterChip(selected = link == NetworkLink.PHONE_HOTSPOT, onClick = {
                    link = NetworkLink.PHONE_HOTSPOT
                }, label = { Text(stringResource(R.string.spike_link_hotspot)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::toggleEchoServer) {
                    Text(stringResource(if (state.echoServerRunning) R.string.spike_echo_stop else R.string.spike_echo_start))
                }
                OutlinedTextField(host, {
                    host = it.trim()
                }, label = { Text(stringResource(R.string.spike_echo_host)) }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(enabled = idle && host.isNotEmpty(), onClick = { viewModel.run(listOf(SpikeTest.NETWORK), netHost = host, netLink = link) }) {
                    Text(stringResource(R.string.spike_measure))
                }
            }
            Text(stringResource(R.string.spike_monitor), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.spike_monitor_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = idle, onClick = { viewModel.run(listOf(SpikeTest.MONITOR_CAMERA), netLink = link, export = true) }) {
                    Text(stringResource(R.string.spike_monitor_camera))
                }
                FilledTonalButton(enabled = idle && host.isNotEmpty(), onClick = {
                    viewModel.run(listOf(SpikeTest.MONITOR_VIEW), netHost = host, netLink = link, export = true)
                }) {
                    Text(stringResource(R.string.spike_monitor_view))
                }
            }
        }
        item {
            HorizontalDivider()
            Text(stringResource(R.string.spike_results), style = MaterialTheme.typography.titleMedium)
        }
        items(SpikeSummary.lines(state.report)) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
