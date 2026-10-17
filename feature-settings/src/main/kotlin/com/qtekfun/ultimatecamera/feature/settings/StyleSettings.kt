package com.qtekfun.ultimatecamera.feature.settings

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
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.label
import com.qtekfun.ultimatecamera.core.settings.StyleDefaults

/**
 * Settings › Styles (D74): the default photo style (the photo chip starts from it), the default video style (recorded
 * when the look picker has no recording look) and whether photos keep an unstyled copy.
 */
@Composable
internal fun StyleSettings(defaults: StyleDefaults, onPhoto: (String?) -> Unit, onVideo: (String?) -> Unit, onKeepBase: (Boolean) -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_styles), R.drawable.ic_set_display) {
        StylePresetItem(stringResource(R.string.settings_style_photo), null, defaults.photo, onPhoto)
        StylePresetItem(stringResource(R.string.settings_style_video), stringResource(R.string.settings_style_video_hint), defaults.video, onVideo)
        SettingsSwitchItem(
            title = stringResource(R.string.settings_style_keep_base),
            description = stringResource(R.string.settings_style_keep_base_hint),
            checked = defaults.keepBase,
            onCheckedChange = onKeepBase
        )
    }
}

/** Standard is stored as no token; any other preset as its default token. */
internal fun presetToken(preset: StylePreset): String? = if (preset == StylePreset.STANDARD) null else StyleParams.of(preset).token

@Composable
private fun StylePresetItem(title: String, description: String?, token: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = StyleParams.parse(token)?.preset ?: StylePreset.STANDARD
    Box {
        SettingsNavItem(title, description, onClick = { open = true }, value = stringResource(current.label))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StylePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(stringResource(preset.label) + if (preset == current) " ✓" else "") },
                    onClick = {
                        open = false
                        onPick(presetToken(preset))
                    }
                )
            }
        }
    }
}
