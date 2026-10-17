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
import com.qtekfun.ultimatecamera.core.settings.PhotoHdrDefault

/** Settings › Photo (D64): the photo mode's default HDR, automatic (the best the phone offers) or off. */
@Composable
internal fun PhotoSettings(mode: PhotoHdrDefault, onSelect: (PhotoHdrDefault) -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_photo), R.drawable.ic_set_display) {
        Text(
            stringResource(R.string.settings_photo_hdr_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
        )
        Column(Modifier.selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp)) {
            PhotoHdrDefault.entries.forEach { option ->
                RadioRow(
                    label = stringResource(if (option == PhotoHdrDefault.AUTO) R.string.settings_photo_hdr_auto else R.string.settings_photo_hdr_off),
                    selected = option == mode,
                    onSelect = { onSelect(option) }
                )
            }
        }
    }
}
