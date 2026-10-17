package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.recording.HdrViewfinder
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip

/**
 * HDR on the camera screen (D63): reports whether the display can show HDR, switches the window to the HDR colour mode
 * while the preview is HDR (back to the default when it is not, and when the screen goes away), and shows the
 * experimental "HDR (sensor)" toggle where it is offered.
 */
@Composable
fun HdrLayer(wire: HdrWire, recording: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) { wire.setDisplayHdr(displayIsHdr(context)) }
    val state by wire.state.collectAsStateWithLifecycle()
    val window = LocalActivity.current?.window
    if (window != null) {
        DisposableEffect(window, state.previewHdr) {
            setHdr(window, state.previewHdr)
            onDispose { setHdr(window, false) }
        }
    }
    if (state.sensorAvailable) {
        val label = stringResource(R.string.camera_sensor_hdr)
        QuickChip(
            label,
            onClick = wire::toggleSensorHdr,
            modifier = modifier,
            enabled = !recording,
            active = state.sensorOn,
            contentDescription = "$label: ${stringResource(if (state.sensorOn) R.string.camera_on else R.string.camera_off)}"
        )
    }
}

/** HDR panel and HDR output for this window's display. */
private fun displayIsHdr(context: Context): Boolean {
    val display = context.display ?: return false
    return display.isHdr && context.resources.configuration.isScreenHdr
}

private fun setHdr(window: Window, on: Boolean) {
    window.colorMode = if (on) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // 0 = no preference (the system default); the viewfinder asks for a moderate headroom only while HDR.
        window.desiredHdrHeadroom = if (on) HdrViewfinder.DESIRED_HEADROOM else 0f
    }
}

/** Below the status strip (and, in portrait, below the monitor button). */
internal val HDR_CHIP_TOP = 96.dp
internal val HDR_CHIP_TOP_PORTRAIT = 160.dp
