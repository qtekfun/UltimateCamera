package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.R
import com.qtekfun.ultimatecamera.core.net.pairing.PairingError
import com.qtekfun.ultimatecamera.core.net.role.LinkState

/** User-visible string resource ids (values + values-es) for the multicamera states and errors; the UI resolves them. */
object ControlStrings {
    fun of(error: PairingError): Int = when (error) {
        PairingError.NOT_A_PAIRING_CODE -> R.string.net_pairing_error_not_a_code
        PairingError.UNSUPPORTED_VERSION -> R.string.net_pairing_error_version
        PairingError.MALFORMED -> R.string.net_pairing_error_malformed
        PairingError.EXPIRED -> R.string.net_pairing_error_expired
        PairingError.SELF -> R.string.net_pairing_error_self
    }

    fun of(code: RejectCode): Int = when (code) {
        RejectCode.VERSION -> R.string.net_reject_version
        RejectCode.UNKNOWN_DEVICE -> R.string.net_reject_unknown_device
        RejectCode.PAIRING_CLOSED -> R.string.net_reject_pairing_closed
        RejectCode.BAD_PROOF -> R.string.net_reject_bad_proof
        RejectCode.ROLE_NOT_ALLOWED -> R.string.net_reject_role
        RejectCode.PROTOCOL -> R.string.net_reject_protocol
    }

    fun of(state: LinkState): Int = when (state) {
        LinkState.STOPPED -> R.string.net_link_stopped
        LinkState.WAITING -> R.string.net_link_waiting
        LinkState.CONNECTING -> R.string.net_link_connecting
        LinkState.CONNECTED -> R.string.net_link_connected
    }

    fun of(warning: StatusWarning): Int = when (warning) {
        StatusWarning.BATTERY_LOW -> R.string.net_warning_battery_low
        StatusWarning.THERMAL -> R.string.net_warning_thermal
        StatusWarning.STORAGE_LOW -> R.string.net_warning_storage_low
        StatusWarning.CLOCK_NOT_SYNCED -> R.string.net_warning_clock
        StatusWarning.RECORDING_ERROR -> R.string.net_warning_recording_error
    }

    fun of(code: ErrorCode): Int = when (code) {
        ErrorCode.UNKNOWN_MESSAGE -> R.string.net_error_unknown_message
        ErrorCode.UNSUPPORTED -> R.string.net_error_unsupported
        ErrorCode.INVALID_ARGUMENT -> R.string.net_error_invalid_argument
        ErrorCode.ALREADY_RECORDING -> R.string.net_error_already_recording
        ErrorCode.NOT_RECORDING -> R.string.net_error_not_recording
        ErrorCode.BUSY -> R.string.net_error_busy
        ErrorCode.CLOCK_NOT_SYNCED -> R.string.net_error_clock_not_synced
        ErrorCode.STORAGE_FULL -> R.string.net_error_storage_full
        ErrorCode.CAMERA_ERROR -> R.string.net_error_camera
        ErrorCode.TIMED_OUT -> R.string.net_error_timed_out
    }
}
