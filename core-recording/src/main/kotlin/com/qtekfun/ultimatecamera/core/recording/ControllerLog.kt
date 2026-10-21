package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.camera.DiagnosticLog

/** The camera screen's states as lines of the optional diagnostic log (D113). */
internal object ControllerLog {
    /** Logs [state] when the log is on and returns it, so a state change is one expression. */
    fun state(state: CameraState): CameraState {
        DiagnosticLog.log("controller") { line(state) }
        return state
    }

    fun line(state: CameraState): String = when (state) {
        is CameraState.Previewing -> "previewing ${state.profile.label} stream ${state.profile.streamWidth}x${state.profile.streamHeight}"
        is CameraState.Recording -> "recording ${state.profile.label}"
        is CameraState.Failed -> "failed rejected=${state.rejected} ${state.message}"
        CameraState.Closed -> "closed"
    }
}
