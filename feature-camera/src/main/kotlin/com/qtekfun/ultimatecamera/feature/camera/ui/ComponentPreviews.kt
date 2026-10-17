package com.qtekfun.ultimatecamera.feature.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.feature.camera.R

/** A busy, bright-to-dark backdrop: the components must stay legible over a white sky and a black shadow alike. */
@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CameraTheme {
            Box(
                Modifier
                    .background(Brush.linearGradient(listOf(Color(0xFFF2F2F2), Color(0xFF6A8CAF), Color(0xFF101010))))
                    .padding(16.dp)
            ) { content() }
        }
    }
}

@Preview(name = "Status strip")
@Composable
private fun StatusStripPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusStrip(recording = false, timecode = formatTimecode(0, 30), badges = listOf("4K30", "HEVC", "10-BIT")) {
            StatusReadout(R.drawable.ic_cam_storage, "263 min")
            StatusReadout(R.drawable.ic_cam_battery, "80%")
        }
        StatusStrip(recording = true, timecode = formatTimecode(83_400, 30), badges = listOf("4K30", "HEVC", "10-BIT")) {
            StatusReadout(R.drawable.ic_cam_storage, "12 min", tone = Tone.WARNING)
            StatusReadout(R.drawable.ic_cam_battery, "9%", tone = Tone.ALERT)
            StatusReadout(R.drawable.ic_cam_thermal, "HOT", tone = Tone.WARNING)
        }
    }
}

@Preview(name = "Record button")
@Composable
private fun RecordButtonPreview() = PreviewFrame {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RecordButton(recording = false, onToggle = {})
        RecordButton(recording = true, onToggle = {})
        RecordButton(recording = false, onToggle = {}, photo = true)
        RecordButton(recording = false, onToggle = {}, enabled = false)
    }
}

@Preview(name = "Mode switch and chips")
@Composable
private fun ChipsPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeSwitch(photo = false, onSelect = {})
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickChip("HEVC", onClick = {})
            QuickChip("4K30 10-bit", onClick = {})
            QuickChip("Rec.709", onClick = {}, icon = R.drawable.ic_cam_lut)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraIconButton(R.drawable.ic_cam_settings, stringResource(R.string.ui_settings), onClick = {})
            CameraIconButton(R.drawable.ic_cam_monitor, stringResource(R.string.ui_monitor), onClick = {}, selected = true)
            CameraIconButton(R.drawable.ic_cam_tune, stringResource(R.string.ui_controls), onClick = {})
            CameraIconButton(R.drawable.ic_cam_flip, "", onClick = {}, enabled = false)
        }
    }
}

@Preview(name = "Audio meter")
@Composable
private fun AudioMeterPreview() = PreviewFrame {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AudioMeterBars(left = 0.2f, right = 0.35f, clip = false)
        AudioMeterBars(left = 0.7f, right = 0.9f, clip = false)
        AudioMeterBars(left = 1f, right = 0.95f, clip = true)
    }
}

@Preview(name = "Panel and readouts")
@Composable
private fun PanelPreview() = PreviewFrame {
    CameraPanel(title = stringResource(R.string.ui_monitor), onClose = {}) {
        PanelSection("Exposure") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OptionChip("Zebras", selected = true, onClick = {})
                OptionChip("False colour", selected = false, onClick = {})
            }
        }
        Row {
            ValueReadout("ISO", "800", onClick = {})
            ValueReadout("Shutter", "1/50", onClick = {}, active = true)
            ValueReadout("WB", "5600K", onClick = {}, locked = true)
        }
    }
}
