package com.qtekfun.ultimatecamera.feature.settings

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatecamera.core.camera.LensCatalog
import com.qtekfun.ultimatecamera.core.camera.LensKind
import com.qtekfun.ultimatecamera.core.camera.LensReader
import com.qtekfun.ultimatecamera.core.settings.MulticamPrefs

/**
 * Multicamera options (D59): the proxy of each take (REQ-NET-006), and the experimental second local camera
 * (REQ-NET-001), shown only on phones that report concurrent camera combinations, with the pair to record (D62).
 */
@Composable
internal fun MulticamSettings(prefs: MulticamPrefs, onChange: (MulticamPrefs) -> Unit, pair: String?, onPair: (String?) -> Unit) {
    val context = LocalContext.current
    val pairs = remember(context) { concurrentPairs(context) }
    SettingsSwitchItem(
        title = stringResource(R.string.settings_proxy_title),
        description = stringResource(R.string.settings_proxy_description),
        checked = prefs.recordProxy,
        onCheckedChange = { onChange(prefs.copy(recordProxy = it)) }
    )
    if (pairs.isNotEmpty()) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_concurrent_title),
            description = stringResource(R.string.settings_concurrent_description),
            checked = prefs.concurrentCamera,
            onCheckedChange = { onChange(prefs.copy(concurrentCamera = it)) }
        )
        if (prefs.concurrentCamera) SecondCameraPair(pairs, pair, onPair)
    }
}

/** Settings › Multicamera › Second camera: automatic, or one of the pairs the phone reports, named after its lenses. */
@Composable
private fun SecondCameraPair(pairs: List<List<String>>, chosen: String?, onPair: (String?) -> Unit) {
    val context = LocalContext.current
    val catalog = remember(context) { runCatching { LensReader.catalog(context.getSystemService(CameraManager::class.java)) }.getOrNull() }
    var open by remember { mutableStateOf(false) }
    val names = pairs.associate { ids -> ids.joinToString(",") to stringResource(R.string.settings_pair, lensName(ids[0], catalog), lensName(ids[1], catalog)) }
    val automatic = stringResource(R.string.settings_pair_auto)
    Box {
        SettingsNavItem(
            title = stringResource(R.string.settings_pair_title),
            description = stringResource(R.string.settings_pair_description),
            onClick = { open = true },
            value = names[chosen] ?: automatic
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(automatic) },
                onClick = {
                    open = false
                    onPair(null)
                }
            )
            names.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onPair(key)
                    }
                )
            }
        }
    }
}

@Composable
private fun lensName(id: String, catalog: LensCatalog?): String = when (catalog?.kindOf(id)) {
    LensKind.MAIN -> stringResource(R.string.settings_lens_main)
    LensKind.ULTRA_WIDE -> stringResource(R.string.settings_lens_ultra_wide)
    LensKind.CROP -> stringResource(R.string.settings_lens_crop)
    LensKind.TELE -> stringResource(R.string.settings_lens_tele, LensCatalog.formatFactor(catalog.factorOf(id)))
    LensKind.FRONT -> stringResource(R.string.settings_lens_front)
    null -> stringResource(R.string.settings_lens_camera, id)
}

/**
 * Every pair of cameras inside the combinations the phone lists as able to stream at the same time, ids sorted (the
 * stored form, as `ConcurrentPairs.key`); empty when there are none.
 */
private fun concurrentPairs(context: Context): List<List<String>> = runCatching {
    val order = compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }
    context.getSystemService(CameraManager::class.java).concurrentCameraIds.flatMap { set ->
        val ids = set.sortedWith(order)
        ids.indices.flatMap { i -> (i + 1 until ids.size).map { j -> listOf(ids[i], ids[j]) } }
    }.distinct()
}.getOrDefault(emptyList())
