package com.qtekfun.ultimatecamera.feature.multicam.roles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.feature.multicam.R
import com.qtekfun.ultimatecamera.feature.multicam.monitor.MulticamControlRoute
import com.qtekfun.ultimatecamera.feature.multicam.monitor.RemoteCamerasViewModel
import com.qtekfun.ultimatecamera.feature.multicam.monitor.vibrateOnce
import com.qtekfun.ultimatecamera.feature.multicam.pairing.DiscoveryStatus
import com.qtekfun.ultimatecamera.feature.multicam.pairing.EnterCodePage
import com.qtekfun.ultimatecamera.feature.multicam.pairing.LastSeen
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingProgressView
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ScanPage
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ShowCodePage

/** Pages of the multicamera screen; [HOME] is the role choice, pairing entry points, paired phones and discovery. */
private enum class Page { HOME, SHOW_CODE, SCAN, ENTER_CODE, CONTROL }

/** Multicamera setup (REQ-NET-002, REQ-NET-003): reachable from the camera screen; back returns there from [Page.HOME]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MulticamScreen(viewModel: MulticamViewModel, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    val goHome = {
        viewModel.resetProgress()
        page = Page.HOME
    }
    BackHandler { if (page == Page.HOME) onClose() else goHome() }
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }
    if (page == Page.CONTROL) {
        ControlPage(viewModel, onBack = goHome)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multicam_title)) },
                navigationIcon = { TextButton(onClick = { if (page == Page.HOME) onClose() else goHome() }) { Text(stringResource(R.string.multicam_back)) } }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                Page.HOME -> HomePage(viewModel, onOpen = { page = it })
                Page.CONTROL -> Unit // shown full screen above
                Page.SHOW_CODE -> {
                    DisposableEffect(Unit) {
                        viewModel.startShowingCode()
                        onDispose { viewModel.stopShowingCode() }
                    }
                    val state by viewModel.hostState.collectAsStateWithLifecycle()
                    Column(Modifier.verticalScroll(rememberScrollState())) { ShowCodePage(state, onNewCode = viewModel::newCode, onDone = goHome) }
                }
                Page.SCAN -> {
                    val progress by viewModel.progress.collectAsStateWithLifecycle()
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ScanPage(progress.view(), onScanned = viewModel::onQrScanned, onRetry = viewModel::resetProgress, onDone = goHome)
                    }
                }
                Page.ENTER_CODE -> {
                    val progress by viewModel.progress.collectAsStateWithLifecycle()
                    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
                    val ownId by viewModel.ownId.collectAsStateWithLifecycle()
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EnterCodePage(
                            nearby = discovery.devices(ownId).filter { it.role == DeviceRole.CAMERA },
                            progress = progress.view(),
                            onPair = viewModel::pairWithCode,
                            onRetry = viewModel::resetProgress,
                            onDone = goHome
                        )
                    }
                }
            }
        }
    }
}

private fun PairingProgress.view() = when (this) {
    PairingProgress.Idle -> PairingProgressView()
    PairingProgress.Working -> PairingProgressView(working = true)
    is PairingProgress.Done -> PairingProgressView(done = name)
    is PairingProgress.Error -> PairingProgressView(error = message)
}

@Composable
private fun HomePage(viewModel: MulticamViewModel, onOpen: (Page) -> Unit) {
    val role by viewModel.role.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val ownId by viewModel.ownId.collectAsStateWithLifecycle()
    val keysProtected by viewModel.keysProtected.collectAsStateWithLifecycle()
    var toUnpair by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(R.string.multicam_role_section) }
        item { RoleRow(null, role, R.string.multicam_role_off, R.string.multicam_role_off_desc, viewModel::setRole) }
        item { RoleRow(MulticamRole.CAMERA, role, R.string.multicam_role_camera, R.string.multicam_role_camera_desc, viewModel::setRole) }
        item { RoleRow(MulticamRole.MONITOR, role, R.string.multicam_role_monitor, R.string.multicam_role_monitor_desc, viewModel::setRole) }
        item { RoleRow(MulticamRole.MASTER, role, R.string.multicam_role_master, R.string.multicam_role_master_desc, viewModel::setRole) }

        item { SectionTitle(R.string.multicam_pair_section) }
        item { PairingEntries(role, hasCameras = devices.any { it.role == DeviceRole.CAMERA }, onOpen) }

        item { SectionTitle(R.string.multicam_devices_section) }
        if (!keysProtected) item { Note(stringResource(R.string.multicam_keys_unprotected), error = true) }
        if (devices.isEmpty()) item { Note(stringResource(R.string.multicam_devices_empty)) }
        items(devices, key = { it.id }) { device -> PairedRow(device, discovery.isOnline(device.id), onUnpair = { toUnpair = device.id }) }

        item { SectionTitle(R.string.multicam_discovery_section) }
        item { Note(discoveryText(discovery, ownId)) }
        items(discovery.devices(ownId), key = { "nearby-" + it.deviceId }) { nearby ->
            ListItem(
                headlineContent = { Text(nearby.name) },
                supportingContent = { Text(roleName(nearby.role)) },
                trailingContent = { if (devices.any { it.id == nearby.deviceId }) Text(stringResource(R.string.multicam_paired_badge)) }
            )
        }
    }

    devices.firstOrNull { it.id == toUnpair }?.let { device ->
        AlertDialog(
            onDismissRequest = { toUnpair = null },
            title = { Text(stringResource(R.string.multicam_unpair_title, device.name)) },
            text = { Text(stringResource(R.string.multicam_unpair_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unpair(device.id)
                    toUnpair = null
                }) { Text(stringResource(R.string.multicam_unpair)) }
            },
            dismissButton = { TextButton(onClick = { toUnpair = null }) { Text(stringResource(R.string.multicam_cancel)) } }
        )
    }
}

@Composable
private fun PairingEntries(role: MulticamRole?, hasCameras: Boolean, onOpen: (Page) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (role) {
            null -> Text(stringResource(R.string.multicam_pick_role_first), style = MaterialTheme.typography.bodyMedium)
            MulticamRole.CAMERA -> Button(onClick = { onOpen(Page.SHOW_CODE) }) { Text(stringResource(R.string.multicam_show_code)) }
            MulticamRole.MONITOR, MulticamRole.MASTER -> {
                Button(onClick = { onOpen(Page.CONTROL) }, enabled = hasCameras) {
                    Text(stringResource(if (role == MulticamRole.MASTER) R.string.multicam_open_master else R.string.multicam_open_monitor))
                }
                if (!hasCameras) Text(stringResource(R.string.multicam_pair_camera_first), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(Page.SCAN) }) { Text(stringResource(R.string.multicam_scan_code)) }
                    OutlinedButton(onClick = { onOpen(Page.ENTER_CODE) }) { Text(stringResource(R.string.multicam_enter_code)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun RoleRow(value: MulticamRole?, current: MulticamRole?, title: Int, description: Int, onSelect: (MulticamRole?) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        leadingContent = { RadioButton(selected = value == current, onClick = null) },
        modifier = Modifier.selectable(selected = value == current, role = Role.RadioButton) { onSelect(value) }
    )
}

@Composable
private fun PairedRow(device: PairedDevice, online: Boolean, onUnpair: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text("${roleName(device.role)} · ${lastSeenText(LastSeen.of(device.lastSeenEpochMs, System.currentTimeMillis()))}") },
        leadingContent = {
            val color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(10.dp).background(color, CircleShape))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (online) Text(stringResource(R.string.multicam_online), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onUnpair) { Text(stringResource(R.string.multicam_unpair)) }
            }
        }
    )
}

@Composable
private fun roleName(role: DeviceRole): String = stringResource(
    when (role) {
        DeviceRole.CAMERA -> R.string.multicam_role_camera
        DeviceRole.MONITOR -> R.string.multicam_role_monitor
        DeviceRole.MASTER -> R.string.multicam_role_master
    }
)

@Composable
private fun lastSeenText(seen: LastSeen): String = when (seen) {
    LastSeen.Never -> stringResource(R.string.multicam_seen_never)
    LastSeen.JustNow -> stringResource(R.string.multicam_seen_now)
    is LastSeen.Minutes -> stringResource(R.string.multicam_seen_minutes, seen.value.toInt())
    is LastSeen.Hours -> stringResource(R.string.multicam_seen_hours, seen.value.toInt())
    is LastSeen.Days -> stringResource(R.string.multicam_seen_days, seen.value.toInt())
}

@Composable
private fun discoveryText(status: DiscoveryStatus, ownId: String): String = when (status.state) {
    DiscoveryStatus.State.IDLE -> stringResource(R.string.multicam_discovery_idle)
    DiscoveryStatus.State.FAILED -> stringResource(R.string.multicam_discovery_failed, status.failureCode ?: 0)
    DiscoveryStatus.State.SEARCHING -> {
        val count = status.devices(ownId).size
        if (count == 0) stringResource(R.string.multicam_discovery_searching) else stringResource(R.string.multicam_discovery_found, count)
    }
}

/**
 * The monitor/master screens of `feature-multicam/monitor` over the paired cameras. Their view model lives in a store
 * owned by this page, so leaving it closes every control link.
 */
@Composable
private fun ControlPage(viewModel: MulticamViewModel, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val local = viewModel.controllerIdentity()
    val targets = remember { viewModel.controlTargets() }
    if (local == null || targets.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val owner = remember(store) {
        object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val remote: RemoteCamerasViewModel = viewModel(
            factory = RemoteCamerasViewModel.factory(targets, local, viewModel.syncedStart, viewModel::commonNow, vibrate = { vibrateOnce(context) })
        )
        MulticamControlRoute(remote, onBack)
    }
}
