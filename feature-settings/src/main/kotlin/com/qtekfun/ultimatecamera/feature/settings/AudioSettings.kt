package com.qtekfun.ultimatecamera.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.audio.AudioInputKind
import com.qtekfun.ultimatecamera.core.audio.AudioSource
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import kotlin.math.roundToInt

/** Settings › Audio: input device (REQ-AUD-001, REQ-AUD-005), gain (REQ-AUD-004) and separate WAV (REQ-AUD-003). */
@Composable
internal fun AudioSettings(viewModel: SettingsViewModel, settings: AppSettings) {
    val input by viewModel.audioInput.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_audio_input), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.selectableGroup()) {
            ChoiceRow(stringResource(R.string.settings_audio_input_automatic), selected = input.storedKey == null || input.pickMissing) {
                viewModel.setAudioInput(null)
            }
            input.available.forEach { source ->
                ChoiceRow(sourceLabel(source), selected = source.key == input.storedKey) { viewModel.setAudioInput(source.key) }
            }
        }
        val effective = input.effective
        when {
            effective == null -> Warning(stringResource(R.string.settings_audio_no_input))
            input.pickMissing -> Warning(stringResource(R.string.settings_audio_input_missing, sourceLabel(effective)))
            else -> Secondary(stringResource(R.string.settings_audio_input_using, sourceLabel(effective)))
        }
        if (effective?.callQuality == true) Warning(stringResource(R.string.settings_audio_call_quality))

        GainSlider(settings.audioGainDb, viewModel::setAudioGainDb)

        SwitchRow(
            title = stringResource(R.string.settings_audio_separate_wav_title),
            description = stringResource(R.string.settings_audio_separate_wav_description),
            checked = settings.separateWav,
            onCheckedChange = viewModel::setSeparateWav
        )
        Secondary(stringResource(R.string.settings_audio_aac))
    }
}

@Composable
private fun GainSlider(storedDb: Float, onDone: (Float) -> Unit) {
    // Local while dragging; written to disk once, when the finger lifts.
    var dragging by remember(storedDb) { mutableFloatStateOf(storedDb) }
    Text(
        stringResource(R.string.settings_audio_gain, dragging.roundToInt()),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
    Slider(
        value = dragging,
        onValueChange = { dragging = it.roundToInt().toFloat() },
        onValueChangeFinished = { onDone(dragging) },
        valueRange = AppSettings.AUDIO_GAIN_MIN_DB..AppSettings.AUDIO_GAIN_MAX_DB,
        steps = (AppSettings.AUDIO_GAIN_MAX_DB - AppSettings.AUDIO_GAIN_MIN_DB).toInt() - 1
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun sourceLabel(source: AudioSource): String = stringResource(R.string.settings_audio_device, stringResource(kindLabel(source.kind)), source.name)

private fun kindLabel(kind: AudioInputKind): Int = when (kind) {
    AudioInputKind.USB -> R.string.settings_audio_kind_usb
    AudioInputKind.BLUETOOTH -> R.string.settings_audio_kind_bluetooth
    AudioInputKind.WIRED -> R.string.settings_audio_kind_wired
    AudioInputKind.BUILTIN -> R.string.settings_audio_kind_builtin
    AudioInputKind.OTHER -> R.string.settings_audio_kind_other
}
