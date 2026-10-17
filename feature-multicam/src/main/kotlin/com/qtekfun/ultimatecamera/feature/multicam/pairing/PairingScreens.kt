package com.qtekfun.ultimatecamera.feature.multicam.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.common.BitMatrix
import com.qtekfun.ultimatecamera.feature.multicam.R
import kotlinx.coroutines.delay

/** Camera side: the QR, the short code, its countdown and the result. */
@Composable
fun ShowCodePage(state: HostState, onNewCode: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (state) {
            HostState.Idle -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.multicam_starting))
            }
            HostState.NoNetwork -> Text(stringResource(R.string.multicam_no_network), textAlign = TextAlign.Center)
            HostState.Failed -> Text(stringResource(R.string.multicam_host_failed), textAlign = TextAlign.Center)
            is HostState.Paired -> {
                Text(stringResource(R.string.multicam_paired_with, state.name), style = MaterialTheme.typography.titleLarge)
                Button(onClick = onNewCode) { Text(stringResource(R.string.multicam_pair_another)) }
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.multicam_done)) }
            }
            is HostState.Showing -> {
                Text(stringResource(R.string.multicam_show_hint), textAlign = TextAlign.Center)
                val matrix = remember(state.qrText) { QrCodes.encode(state.qrText) }
                QrImage(matrix, Modifier.widthIn(max = 320.dp).fillMaxWidth())
                Text(ShortCode.format(state.code), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                Countdown(state.expiresAtMs)
                OutlinedButton(onClick = onNewCode) { Text(stringResource(R.string.multicam_new_code)) }
            }
        }
    }
}

@Composable
private fun Countdown(expiresAtMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val left = ((expiresAtMs - now) / 1_000).coerceAtLeast(0)
    Text(stringResource(R.string.multicam_expires_in, (left / 60).toInt(), (left % 60).toInt()), style = MaterialTheme.typography.bodyMedium)
}

/** Black modules on white (scanners expect dark on light whatever the theme), square. */
@Composable
fun QrImage(matrix: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Monitor side: a live scanner; a decoded text goes to [onScanned]. */
@Composable
fun ScanPage(progress: PairingProgressView, onScanned: (String) -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var scannerFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!granted) {
            Text(stringResource(R.string.multicam_camera_permission), textAlign = TextAlign.Center)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.multicam_grant)) }
            return@Column
        }
        Text(stringResource(R.string.multicam_scan_hint), textAlign = TextAlign.Center)
        if (progress.done == null) {
            ScannerView(onScanned = onScanned, onError = {
                scannerFailed = true
            }, rearmKey = progress.error, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
        }
        if (scannerFailed) Text(stringResource(R.string.multicam_scanner_error), color = MaterialTheme.colorScheme.error)
        ProgressLine(progress, onRetry, onDone)
    }
}

@Composable
private fun ScannerView(onScanned: (String) -> Unit, onError: () -> Unit, rearmKey: Int?, modifier: Modifier) {
    val context = LocalContext.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentOnError by rememberUpdatedState(onError)
    val scanner = remember {
        QrScanner(
            context.applicationContext,
            onText = { text -> ContextCompat.getMainExecutor(context).execute { currentOnScanned(text) } },
            onError = { ContextCompat.getMainExecutor(context).execute { currentOnError() } }
        )
    }
    DisposableEffect(scanner) { onDispose { scanner.close() } }
    // After an error is shown or dismissed, the same code may be scanned again.
    LaunchedEffect(rearmKey) { scanner.rearm() }
    Box(modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        AndroidView(factory = { TextureView(it).apply { surfaceTextureListener = scanner } }, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
    }
}

/** What the pairing pages need of [com.qtekfun.ultimatecamera.feature.multicam.roles.PairingProgress], flattened. */
data class PairingProgressView(val working: Boolean = false, val done: String? = null, val error: Int? = null)

@Composable
private fun ProgressLine(progress: PairingProgressView, onRetry: () -> Unit, onDone: () -> Unit) {
    when {
        progress.working -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.multicam_pairing))
        }
        progress.done != null -> {
            Text(stringResource(R.string.multicam_paired_with, progress.done), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onDone) { Text(stringResource(R.string.multicam_done)) }
        }
        progress.error != null -> {
            Text(stringResource(progress.error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.multicam_try_again)) }
        }
    }
}

/** Monitor side without a camera: pick a phone found on the LAN and type its code. */
@Composable
fun EnterCodePage(nearby: List<NearbyDevice>, progress: PairingProgressView, onPair: (NearbyDevice, String) -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (nearby.isEmpty()) {
            Text(stringResource(R.string.multicam_code_no_devices))
        } else {
            Text(stringResource(R.string.multicam_code_pick_device))
            nearby.forEach { device ->
                ListItem(
                    headlineContent = { Text(device.name) },
                    leadingContent = { RadioButton(selected = device.deviceId == selectedId, onClick = null) },
                    modifier = Modifier.selectable(selected = device.deviceId == selectedId, role = Role.RadioButton) { selectedId = device.deviceId }
                )
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { new -> code = new.filter { it.isDigit() || it == ' ' }.take(ShortCode.DIGITS + 1) },
            label = { Text(stringResource(R.string.multicam_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        val device = nearby.firstOrNull { it.deviceId == selectedId }
        Button(onClick = { device?.let { onPair(it, code) } }, enabled = device != null && !progress.working) { Text(stringResource(R.string.multicam_pair)) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgressLine(progress, onRetry, onDone)
        }
    }
}
