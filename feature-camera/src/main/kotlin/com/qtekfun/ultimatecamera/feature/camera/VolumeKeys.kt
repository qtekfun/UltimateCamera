package com.qtekfun.ultimatecamera.feature.camera

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/** What a key event does on the camera screen. */
enum class VolumeKeyAction {
    /** Not a volume key: the system handles it. */
    PASS,

    /** Start or stop recording. */
    TOGGLE,

    /** A volume key's release or auto-repeat: swallowed so the volume panel does not appear. */
    CONSUME
}

/**
 * Volume up/down start and stop recording on the camera screen (D47), as on most camera apps, so a take can be started
 * with gloves or on a rig without touching the screen. The activity forwards its key events to [dispatch]; the camera
 * screen installs the handler with [VolumeKeyRecording] only while it is shown and the setting is on.
 */
object VolumeKeys {
    @Volatile private var handler: (() -> Unit)? = null

    /** Called from `Activity.dispatchKeyEvent`; true when the event was used. */
    fun dispatch(event: KeyEvent): Boolean {
        val h = handler ?: return false
        return when (action(event.keyCode, event.action, event.repeatCount)) {
            VolumeKeyAction.PASS -> false
            VolumeKeyAction.TOGGLE -> {
                h()
                true
            }
            VolumeKeyAction.CONSUME -> true
        }
    }

    /** Only the first press of a volume key toggles; holding it (repeats) or releasing it does nothing more. */
    fun action(keyCode: Int, keyAction: Int, repeatCount: Int): VolumeKeyAction = when {
        keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKeyAction.PASS
        keyAction == KeyEvent.ACTION_DOWN && repeatCount == 0 -> VolumeKeyAction.TOGGLE
        else -> VolumeKeyAction.CONSUME
    }

    internal fun install(onToggle: () -> Unit) {
        handler = onToggle
    }

    internal fun uninstall(onToggle: () -> Unit) {
        if (handler === onToggle) handler = null
    }
}

/** Routes the volume keys to [onToggle] while this is composed and [enabled]. */
@Composable
fun VolumeKeyRecording(enabled: Boolean, onToggle: () -> Unit) {
    val current = rememberUpdatedState(onToggle)
    DisposableEffect(enabled) {
        val handler: () -> Unit = { current.value() }
        if (enabled) VolumeKeys.install(handler)
        onDispose { VolumeKeys.uninstall(handler) }
    }
}
