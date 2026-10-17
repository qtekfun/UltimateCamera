package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.camera.HdrChoice
import com.qtekfun.ultimatecamera.core.camera.HdrMethod
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip

/**
 * The photo HDR chip (D64), photo mode only: "HDR Auto · <method>" or "HDR Off"; the menu offers Auto, Off and every
 * method this camera really has (the maker's HDR, Auto or Night extensions, Ultra HDR, our own fusion).
 */
@Composable
internal fun PhotoHdrChip(viewModel: CameraViewModel) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode != CaptureMode.PHOTO) return
    val camera by viewModel.photo.camera.collectAsStateWithLifecycle()
    val ready = camera as? PhotoState.Ready ?: return
    var open by remember { mutableStateOf(false) }
    val gainMap = ready.hdr.ultraHdr && ready.hdr.method != HdrMethod.ULTRA_HDR
    val method = if (gainMap) stringResource(R.string.photo_hdr_with_gain_map, methodName(ready.hdr.method)) else methodName(ready.hdr.method)
    val label = when (val choice = ui.hdr) {
        HdrChoice.Auto -> if (ready.hdr.method == HdrMethod.OFF) {
            stringResource(R.string.photo_hdr_auto_none)
        } else {
            stringResource(R.string.photo_hdr_auto, method)
        }
        HdrChoice.Off -> stringResource(R.string.photo_hdr_off)
        is HdrChoice.Manual -> stringResource(R.string.photo_hdr_manual, methodName(choice.method))
    }
    Box {
        QuickChip(label, onClick = { open = true }, enabled = !ready.capturing, active = open, contentDescription = label)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val choices = listOf(HdrChoice.Auto, HdrChoice.Off) + ready.hdrMethods.filter { it != HdrMethod.OFF }.map { HdrChoice.Manual(it) }
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choiceName(choice) + if (choice == ui.hdr) " ✓" else "") },
                    onClick = {
                        open = false
                        viewModel.setPhotoHdr(choice)
                    }
                )
            }
        }
    }
}

@Composable
private fun choiceName(choice: HdrChoice): String = when (choice) {
    HdrChoice.Auto -> stringResource(R.string.photo_hdr_menu_auto)
    HdrChoice.Off -> stringResource(R.string.photo_hdr_menu_off)
    is HdrChoice.Manual -> methodName(choice.method)
}

@Composable
private fun methodName(method: HdrMethod): String = stringResource(
    when (method) {
        HdrMethod.EXTENSION_HDR -> R.string.photo_hdr_method_extension
        HdrMethod.EXTENSION_AUTO -> R.string.photo_hdr_method_extension_auto
        HdrMethod.EXTENSION_NIGHT -> R.string.photo_hdr_method_night
        HdrMethod.ULTRA_HDR -> R.string.photo_hdr_method_ultra
        HdrMethod.FUSION -> R.string.photo_hdr_method_fusion
        HdrMethod.OFF -> R.string.photo_hdr_method_off
    }
)
