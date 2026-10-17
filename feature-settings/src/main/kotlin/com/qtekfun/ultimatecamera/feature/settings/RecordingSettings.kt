package com.qtekfun.ultimatecamera.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.core.settings.VideoContainerChoice

/**
 * Settings › Recording (D69): the take's container, MP4 (default) or MOV (QuickTime); and the background blur in video
 * switch (D89, off by default; hidden when [portrait] is null).
 */
@Composable
internal fun RecordingSettings(
    choice: VideoContainerChoice,
    onSelect: (VideoContainerChoice) -> Unit,
    portrait: Boolean? = null,
    onPortrait: (Boolean) -> Unit = {}
) {
    SettingsSection(stringResource(R.string.settings_section_recording), R.drawable.ic_set_storage) {
        Text(
            stringResource(R.string.settings_container_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
        )
        Column(Modifier.selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp)) {
            VideoContainerChoice.entries.forEach { option ->
                RadioRow(
                    label = stringResource(
                        when (option) {
                            VideoContainerChoice.MP4 -> R.string.settings_container_mp4
                            VideoContainerChoice.MOV -> R.string.settings_container_mov
                        }
                    ),
                    selected = option == choice,
                    onSelect = { onSelect(option) }
                )
            }
        }
        Text(
            stringResource(R.string.settings_container_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        )
        if (portrait != null) {
            SettingsSwitchItem(
                title = stringResource(R.string.settings_video_portrait_title),
                description = stringResource(R.string.settings_video_portrait_description),
                checked = portrait,
                onCheckedChange = onPortrait
            )
        }
    }
}
