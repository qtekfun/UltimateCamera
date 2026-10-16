package com.qtekfun.ultimatecamera.core.net.control

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Role of a phone in a multicamera setup (REQ-NET-003). A phone has exactly one role per session.
 *
 * - [MASTER] coordinates several cameras, is the common clock source (REQ-NET-005) and receives files (REQ-NET-006).
 * - [CAMERA] records locally, always; it obeys commands from its paired master and monitors.
 * - [MONITOR] shows one camera's live view and starts/stops its recording.
 */
@Serializable
enum class DeviceRole(val wire: String) {
    @SerialName("master")
    MASTER("master"),

    @SerialName("camera")
    CAMERA("camera"),

    @SerialName("monitor")
    MONITOR("monitor");

    /** True for the roles that send commands to a camera. */
    val isController: Boolean get() = this != CAMERA

    companion object {
        fun fromWire(value: String): DeviceRole? = entries.firstOrNull { it.wire == value }
    }
}
