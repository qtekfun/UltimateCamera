package com.qtekfun.ultimatecamera.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.R

/** Top-level screens. A plain enum instead of a navigation library: few screens, no extra dependency (F-Droid). */
enum class Destination { HOME, SETTINGS, DIAGNOSTICS }

/**
 * The app's screens and the back stack between them (one level: every screen returns to [Destination.HOME]).
 * [diagnostics] is the Phase 0 spike bench, kept reachable until the camera (Phase 2) replaces the home screen.
 *
 * [maxBrightness] (REQ-MON-003, persisted, off by default) applies on every screen except Diagnostics, whose own switch
 * is part of what the spike records (thermal runs start at system brightness).
 */
@Composable
fun AppRoot(start: Destination, maxBrightness: Boolean, settings: @Composable () -> Unit, diagnostics: @Composable () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(start) }
    BackHandler(enabled = destination != Destination.HOME) { destination = Destination.HOME }
    if (destination != Destination.DIAGNOSTICS) ScreenBrightness(full = maxBrightness)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (destination) {
            Destination.HOME -> HomeScreen(onOpen = { destination = it })
            Destination.SETTINGS -> settings()
            Destination.DIAGNOSTICS -> diagnostics()
        }
    }
}

/** Forces full screen brightness while [full]; otherwise (and when it leaves the composition) the system decides. */
@Composable
private fun ScreenBrightness(full: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(full, window) {
        fun apply(level: Float) {
            window.attributes = window.attributes.apply { screenBrightness = level }
        }
        apply(if (full) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

@Composable
private fun HomeScreen(onOpen: (Destination) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.home_preview_note),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onOpen(Destination.DIAGNOSTICS) }) { Text(stringResource(R.string.home_open_diagnostics)) }
            FilledTonalButton(onClick = { onOpen(Destination.SETTINGS) }) { Text(stringResource(R.string.home_open_settings)) }
        }
    }
}
