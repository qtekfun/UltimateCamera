package com.qtekfun.ultimatecamera.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive with the wallpaper's dynamic colours (always available on minSdk 33).
 *
 * Dark only for now: the camera is a field monitor and a light UI around the viewfinder distorts how the image is judged.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UltimateCameraTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = dynamicDarkColorScheme(LocalContext.current),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
