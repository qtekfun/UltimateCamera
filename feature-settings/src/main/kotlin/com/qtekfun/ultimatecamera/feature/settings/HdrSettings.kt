package com.qtekfun.ultimatecamera.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatecamera.core.settings.HdrPrefs

/** Settings › Display › HDR viewfinder (D63), shown only where the display can show HDR. */
@Composable
internal fun HdrViewfinderSetting(prefs: HdrPrefs, onChange: (HdrPrefs) -> Unit) {
    val context = LocalContext.current
    val hdrDisplay = remember(context) { displayIsHdr(context) }
    if (!hdrDisplay) return
    SettingsSwitchItem(
        title = stringResource(R.string.settings_hdr_viewfinder_title),
        description = stringResource(R.string.settings_hdr_viewfinder_description),
        checked = prefs.hdrViewfinder,
        onCheckedChange = { onChange(prefs.copy(hdrViewfinder = it)) }
    )
}

/** The Experimental switch of D37: vendor features such as sensor HDR (D63) are offered only with it on. */
@Composable
internal fun ExperimentalSettings(prefs: HdrPrefs, onChange: (HdrPrefs) -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_experimental), R.drawable.ic_set_features) {
        SettingsSwitchItem(
            title = stringResource(R.string.settings_experimental_title),
            description = stringResource(R.string.settings_experimental_description),
            checked = prefs.experimental,
            onCheckedChange = { onChange(prefs.copy(experimental = it)) }
        )
    }
}

private fun displayIsHdr(context: Context): Boolean = runCatching {
    context.display?.isHdr == true && context.resources.configuration.isScreenHdr
}.getOrDefault(false)
