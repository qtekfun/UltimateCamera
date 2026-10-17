package com.qtekfun.ultimatecamera.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Top-level screens. A plain enum instead of a navigation library: few screens, no extra dependency (F-Droid). */
enum class Destination { CAMERA, SETTINGS, DIAGNOSTICS, AUTOCONFIG }

/**
 * The app's screens and the back stack between them (one level: every screen returns to [Destination.CAMERA], the main
 * screen). [diagnostics] is the Phase 0 spike bench, reachable from the camera.
 *
 * [offerAutoConfig] opens [autoConfig] once, at first start (D42); it is skippable and also reachable from Settings.
 *
 * [maxBrightness] (REQ-MON-003, persisted, off by default) applies on every screen except Diagnostics, whose own switch
 * is part of what the spike records (thermal runs start at system brightness).
 */
@Composable
fun AppRoot(
    start: Destination,
    maxBrightness: Boolean,
    camera: @Composable (open: (Destination) -> Unit) -> Unit,
    settings: @Composable (open: (Destination) -> Unit) -> Unit,
    diagnostics: @Composable () -> Unit,
    offerAutoConfig: Boolean = false,
    autoConfig: @Composable (close: () -> Unit) -> Unit = {}
) {
    var destination by rememberSaveable { mutableStateOf(start) }
    var autoConfigOffered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(offerAutoConfig) {
        if (offerAutoConfig && !autoConfigOffered && destination == Destination.CAMERA) destination = Destination.AUTOCONFIG
        if (offerAutoConfig) autoConfigOffered = true
    }
    BackHandler(enabled = destination != Destination.CAMERA) { destination = Destination.CAMERA }
    if (destination != Destination.DIAGNOSTICS) ScreenBrightness(full = maxBrightness)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (destination) {
            Destination.CAMERA -> camera { destination = it }
            Destination.SETTINGS -> settings { destination = it }
            Destination.DIAGNOSTICS -> diagnostics()
            Destination.AUTOCONFIG -> autoConfig { destination = Destination.CAMERA }
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
