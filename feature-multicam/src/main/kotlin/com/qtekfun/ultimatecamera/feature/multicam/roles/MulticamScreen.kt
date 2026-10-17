package com.qtekfun.ultimatecamera.feature.multicam.roles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
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
import com.qtekfun.ultimatecamera.feature.multicam.pairing.HostState
import com.qtekfun.ultimatecamera.feature.multicam.pairing.LastSeen
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingProgressView
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ScanPage
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ShowCodePage
import com.qtekfun.ultimatecamera.feature.multicam.session.Relation
import com.qtekfun.ultimatecamera.feature.multicam.session.RoleChoice
import com.qtekfun.ultimatecamera.feature.multicam.session.SessionRoles

/**
 * Pages of the multicamera screen. [HOME]: connect a phone (show or scan a code), this phone's role, paired phones and
 * discovery; [SESSION]: the roles with one paired phone (D80); [CONTROL]: the monitor or master screens.
 */
internal enum class Page { HOME, SHOW_CODE, SCAN, ENTER_CODE, SESSION, CONTROL }

/** Where a role choice or an incoming assignment takes the screen (pure, unit-tested). */
internal object MulticamNavigation {
    sealed interface Target {
        /** Stay where it is (the roles ended, or nothing changed). */
        data object Stay : Target

        /** Back to the camera screen: the camera role runs there (D56), so the monitor gets its status and video. */
        data object Camera : Target

        /** The monitor (one camera) or master (several) screens. */
        data object Control : Target
    }

    fun afterRoles(own: MulticamRole?): Target = when (own) {
        null -> Target.Stay
        MulticamRole.CAMERA -> Target.Camera
        MulticamRole.MONITOR, MulticamRole.MASTER -> Target.Control
    }
}

/**
 * Multicamera (REQ-NET-002, REQ-NET-003, D80): reachable from the camera screen; back returns there from [Page.HOME].
 * Any two phones pair (either shows the code, the other scans it); right after pairing both show the session page,
 * where one tap decides which phone records; the other phone follows by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MulticamScreen(viewModel: MulticamViewModel, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var peer by rememberSaveable { mutableStateOf<String?>(null) }
    val goHome = {
        viewModel.resetProgress()
        viewModel.resetRoleProgress()
        page = Page.HOME
    }
    val openSession = { id: String ->
        viewModel.resetProgress()
        viewModel.resetRoleProgress()
        peer = id
        page = Page.SESSION
    }
    BackHandler { if (page == Page.HOME) onClose() else goHome() }
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }
    // The other phone made this one its monitor (or master): show the control page.
    val openControl by viewModel.openControl.collectAsStateWithLifecycle()
    LaunchedEffect(openControl) {
        if (openControl) {
            viewModel.consumeOpenControl()
            page = Page.CONTROL
        }
    }
    // A role chosen here: the camera goes back to the camera screen, the monitor opens its control page.
    val roleProgress by viewModel.roleProgress.collectAsStateWithLifecycle()
    LaunchedEffect(roleProgress) {
        val done = roleProgress as? RoleProgress.Done ?: return@LaunchedEffect
        viewModel.resetRoleProgress()
        when (MulticamNavigation.afterRoles(done.own)) {
            MulticamNavigation.Target.Camera -> onClose()
            MulticamNavigation.Target.Control -> page = Page.CONTROL
            MulticamNavigation.Target.Stay -> Unit
        }
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
                Page.HOME -> HomePage(viewModel, onOpen = { page = it }, onSession = openSession, onClose = onClose)
                Page.CONTROL -> Unit // shown full screen above
                Page.SESSION -> {
                    val id = peer
                    if (id == null) {
                        LaunchedEffect(Unit) { goHome() }
                    } else {
                        SessionPage(viewModel, id, onUnpaired = goHome, onCamera = onClose, onControl = { page = Page.CONTROL })
                    }
                }
                Page.SHOW_CODE -> {
                    DisposableEffect(Unit) {
                        viewModel.startShowingCode()
                        onDispose { viewModel.stopShowingCode() }
                    }
                    val state by viewModel.hostState.collectAsStateWithLifecycle()
                    LaunchedEffect(state) { (state as? HostState.Paired)?.let { openSession(it.deviceId) } }
                    Column(Modifier.verticalScroll(rememberScrollState())) { ShowCodePage(state, onNewCode = viewModel::newCode, onDone = goHome) }
                }
                Page.SCAN -> {
                    val progress by viewModel.progress.collectAsStateWithLifecycle()
                    LaunchedEffect(progress) { (progress as? PairingProgress.Done)?.let { openSession(it.deviceId) } }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ScanPage(progress.view(), onScanned = viewModel::onQrScanned, onRetry = viewModel::resetProgress, onDone = goHome)
                    }
                }
                Page.ENTER_CODE -> {
                    val progress by viewModel.progress.collectAsStateWithLifecycle()
                    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
                    val ownId by viewModel.ownId.collectAsStateWithLifecycle()
                    LaunchedEffect(progress) { (progress as? PairingProgress.Done)?.let { openSession(it.deviceId) } }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EnterCodePage(
                            // Any phone may show the code (D80), whatever role it had.
                            nearby = discovery.devices(ownId),
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
private fun HomePage(viewModel: MulticamViewModel, onOpen: (Page) -> Unit, onSession: (String) -> Unit, onClose: () -> Unit) {
    val role by viewModel.role.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val ownId by viewModel.ownId.collectAsStateWithLifecycle()
    val keysProtected by viewModel.keysProtected.collectAsStateWithLifecycle()
    val unavailable by viewModel.unavailable.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(R.string.multicam_connect_section) }
        item { ConnectCard(onOpen) }

        item { SectionTitle(R.string.multicam_this_section) }
        item {
            ThisPhone(
                role = role,
                cameras = devices.count { it.role == DeviceRole.CAMERA },
                onCamera = onClose,
                onControl = { onOpen(Page.CONTROL) },
                onOff = viewModel::turnOff
            )
        }

        item { SectionTitle(R.string.multicam_devices_section) }
        if (unavailable) item { Note(stringResource(R.string.multicam_devices_unavailable), error = true) }
        if (!keysProtected) item { Note(stringResource(R.string.multicam_keys_unprotected), error = true) }
        if (devices.isEmpty()) item { Note(stringResource(R.string.multicam_devices_empty)) }
        items(devices, key = { it.id }) { device ->
            PairedRow(device, SessionRoles.relation(role, device.role), discovery.isOnline(device.id), onClick = { onSession(device.id) })
        }

        item { SectionTitle(R.string.multicam_discovery_section) }
        item { Note(discoveryText(discovery, ownId)) }
    }
}

/** The two ways to pair, for either phone; the roles come afterwards. */
@Composable
private fun ConnectCard(onOpen: (Page) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.multicam_connect_hint), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpen(Page.SHOW_CODE) }) { Text(stringResource(R.string.multicam_show_code)) }
            Button(onClick = { onOpen(Page.SCAN) }) { Text(stringResource(R.string.multicam_scan_code)) }
        }
        TextButton(onClick = { onOpen(Page.ENTER_CODE) }) { Text(stringResource(R.string.multicam_enter_code)) }
    }
}

@Composable
private fun ThisPhone(role: MulticamRole?, cameras: Int, onCamera: () -> Unit, onControl: () -> Unit, onOff: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (role) {
                null -> stringResource(R.string.multicam_this_off)
                MulticamRole.CAMERA -> stringResource(R.string.multicam_this_camera)
                MulticamRole.MONITOR -> stringResource(R.string.multicam_this_monitor)
                MulticamRole.MASTER -> stringResource(R.string.multicam_this_master, cameras)
            },
            style = MaterialTheme.typography.bodyLarge
        )
        when (role) {
            null -> Unit
            MulticamRole.CAMERA -> Button(onClick = onCamera) { Text(stringResource(R.string.multicam_go_camera)) }
            MulticamRole.MONITOR, MulticamRole.MASTER -> Button(onClick = onControl, enabled = cameras > 0) {
                Text(stringResource(if (role == MulticamRole.MASTER) R.string.multicam_open_master else R.string.multicam_open_monitor))
            }
        }
        if (role != null) TextButton(onClick = onOff) { Text(stringResource(R.string.multicam_turn_off)) }
    }
}

/** The roles with one paired phone: pick them (one tap), or open what this phone is, swap, end, unpair. */
@Composable
private fun SessionPage(viewModel: MulticamViewModel, peerId: String, onUnpaired: () -> Unit, onCamera: () -> Unit, onControl: () -> Unit) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    // The page follows role changes, from here or from the other phone.
    val role by viewModel.role.collectAsStateWithLifecycle()
    val progress by viewModel.roleProgress.collectAsStateWithLifecycle()
    var unpairAsked by rememberSaveable { mutableStateOf(false) }
    val ownId by viewModel.ownId.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == peerId } ?: run {
        // Unpaired meanwhile; before the paired list is loaded (no own id yet) wait for it.
        if (ownId.isNotEmpty()) LaunchedEffect(Unit) { onUnpaired() }
        return
    }
    val relation = SessionRoles.relation(role, device.role)
    val working = progress == RoleProgress.Working
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.multicam_paired_with, device.name), style = MaterialTheme.typography.titleLarge)
        if (!discovery.isOnline(peerId)) Text(stringResource(R.string.multicam_session_offline), style = MaterialTheme.typography.bodyMedium)
        when (relation) {
            Relation.NONE -> {
                Text(stringResource(R.string.multicam_session_choose), style = MaterialTheme.typography.titleMedium)
                ChoiceCard(R.string.multicam_role_camera, stringResource(R.string.multicam_choice_camera_desc, device.name), enabled = !working) {
                    viewModel.choose(peerId, RoleChoice.CAMERA)
                }
                ChoiceCard(R.string.multicam_role_monitor, stringResource(R.string.multicam_choice_monitor_desc, device.name), enabled = !working) {
                    viewModel.choose(peerId, RoleChoice.MONITOR)
                }
            }
            Relation.CAMERA_OF_PEER -> {
                Text(stringResource(R.string.multicam_session_is_camera, device.name), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onCamera, enabled = !working) { Text(stringResource(R.string.multicam_go_camera)) }
                OutlinedButton(onClick = { viewModel.choose(peerId, RoleChoice.MONITOR) }, enabled = !working) { Text(stringResource(R.string.multicam_swap)) }
                TextButton(onClick = { viewModel.choose(peerId, RoleChoice.END) }, enabled = !working) { Text(stringResource(R.string.multicam_end)) }
            }
            Relation.CONTROLS_PEER -> {
                Text(stringResource(R.string.multicam_session_is_monitor, device.name), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onControl, enabled = !working) { Text(stringResource(R.string.multicam_open_monitor)) }
                OutlinedButton(onClick = { viewModel.choose(peerId, RoleChoice.CAMERA) }, enabled = !working) { Text(stringResource(R.string.multicam_swap)) }
                TextButton(onClick = { viewModel.choose(peerId, RoleChoice.END) }, enabled = !working) { Text(stringResource(R.string.multicam_end)) }
            }
        }
        when (val p = progress) {
            RoleProgress.Working -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.multicam_roles_working))
            }
            is RoleProgress.Error -> Text(stringResource(p.message), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Start)
            RoleProgress.Idle, is RoleProgress.Done -> Unit
        }
        TextButton(onClick = { unpairAsked = true }, enabled = !working) { Text(stringResource(R.string.multicam_unpair)) }
    }
    if (unpairAsked) {
        AlertDialog(
            onDismissRequest = { unpairAsked = false },
            title = { Text(stringResource(R.string.multicam_unpair_title, device.name)) },
            text = { Text(stringResource(R.string.multicam_unpair_text)) },
            confirmButton = {
                TextButton(onClick = {
                    unpairAsked = false
                    viewModel.unpair(peerId)
                    onUnpaired()
                }) { Text(stringResource(R.string.multicam_unpair)) }
            },
            dismissButton = { TextButton(onClick = { unpairAsked = false }) { Text(stringResource(R.string.multicam_cancel)) } }
        )
    }
}

@Composable
private fun ChoiceCard(title: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
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
private fun PairedRow(device: PairedDevice, relation: Relation, online: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text("${relationName(relation)} · ${lastSeenText(LastSeen.of(device.lastSeenEpochMs, System.currentTimeMillis()))}") },
        leadingContent = {
            val color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(10.dp).background(color, CircleShape))
        },
        trailingContent = { if (online) Text(stringResource(R.string.multicam_online), style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    )
}

@Composable
private fun relationName(relation: Relation): String = stringResource(
    when (relation) {
        Relation.NONE -> R.string.multicam_rel_none
        Relation.CAMERA_OF_PEER -> R.string.multicam_rel_monitor_of_this
        Relation.CONTROLS_PEER -> R.string.multicam_rel_camera
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
