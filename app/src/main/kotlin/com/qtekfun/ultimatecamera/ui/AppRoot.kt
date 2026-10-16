package com.qtekfun.ultimatecamera.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.R

/** Top-level screens. A plain enum instead of a navigation library: few screens, no extra dependency (F-Droid). */
enum class Destination { HOME, DIAGNOSTICS }

/**
 * The app's screens and the back stack between them (one level: every screen returns to [Destination.HOME]).
 * [diagnostics] is the Phase 0 spike bench, kept reachable until the camera (Phase 2) replaces the home screen.
 */
@Composable
fun AppRoot(start: Destination, diagnostics: @Composable () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(start) }
    BackHandler(enabled = destination != Destination.HOME) { destination = Destination.HOME }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (destination) {
            Destination.HOME -> HomeScreen(onOpen = { destination = it })
            Destination.DIAGNOSTICS -> diagnostics()
        }
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
            FilledTonalButton(onClick = {}, enabled = false) { Text(stringResource(R.string.home_open_settings)) }
        }
    }
}
