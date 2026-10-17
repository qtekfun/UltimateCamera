package com.qtekfun.ultimatecamera.feature.settings

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatecamera.core.settings.MulticamPrefs

/**
 * Multicamera options (D59): the proxy of each take (REQ-NET-006), and the experimental second local camera
 * (REQ-NET-001), shown only on phones that report concurrent camera combinations.
 */
@Composable
internal fun MulticamSettings(prefs: MulticamPrefs, onChange: (MulticamPrefs) -> Unit) {
    val context = LocalContext.current
    val concurrent = remember(context) { hasConcurrentCameras(context) }
    SettingsSwitchItem(
        title = stringResource(R.string.settings_proxy_title),
        description = stringResource(R.string.settings_proxy_description),
        checked = prefs.recordProxy,
        onCheckedChange = { onChange(prefs.copy(recordProxy = it)) }
    )
    if (concurrent) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_concurrent_title),
            description = stringResource(R.string.settings_concurrent_description),
            checked = prefs.concurrentCamera,
            onCheckedChange = { onChange(prefs.copy(concurrentCamera = it)) }
        )
    }
}

/** True when the phone lists at least one set of cameras that can stream at the same time. */
private fun hasConcurrentCameras(context: Context): Boolean =
    runCatching { context.getSystemService(CameraManager::class.java).concurrentCameraIds.isNotEmpty() }.getOrDefault(false)
