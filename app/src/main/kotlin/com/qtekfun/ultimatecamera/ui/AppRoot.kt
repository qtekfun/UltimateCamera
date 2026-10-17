package com.qtekfun.ultimatecamera.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Top-level screens. A plain enum instead of a navigation library: few screens, no extra dependency (F-Droid). */
enum class Destination { CAMERA, SETTINGS, DIAGNOSTICS }

/**
 * The app's screens and the back stack between them (one level: every screen returns to [Destination.CAMERA], the main
 * screen). [diagnostics] is the Phase 0 spike bench, reachable from the camera.
 *
 * [maxBrightness] (REQ-MON-003, persisted, off by default) applies on every screen except Diagnostics, whose own switch
 * is part of what the spike records (thermal runs start at system brightness).
 */
@Composable
fun AppRoot(
    start: Destination,
    maxBrightness: Boolean,
    camera: @Composable (open: (Destination) -> Unit) -> Unit,
    settings: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(start) }
    BackHandler(enabled = destination != Destination.CAMERA) { destination = Destination.CAMERA }
    if (destination != Destination.DIAGNOSTICS) ScreenBrightness(full = maxBrightness)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (destination) {
            Destination.CAMERA -> camera { destination = it }
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
