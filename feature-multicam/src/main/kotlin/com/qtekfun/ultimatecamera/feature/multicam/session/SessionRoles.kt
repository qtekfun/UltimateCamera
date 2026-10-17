package com.qtekfun.ultimatecamera.feature.multicam.session

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.feature.multicam.roles.toDeviceRole

/** What this phone does with one paired phone (D80). */
enum class Relation {
    /** Paired, no roles between them yet (or the session ended). */
    NONE,

    /** This phone is the camera; the peer watches and controls it. */
    CAMERA_OF_PEER,

    /** This phone watches and controls the peer, which is a camera. */
    CONTROLS_PEER
}

/** What the user picks for this phone on the session page of a peer. */
enum class RoleChoice { CAMERA, MONITOR, END }

/**
 * The roles after a choice: this phone's role ([own]) and the stored role of each paired phone that changes
 * ([peers], by device id). [request] is what the other phone is asked to take, as sent in `ControlMessage.Roles`.
 */
data class RolePlan(val own: MulticamRole?, val peers: Map<String, DeviceRole>, val sender: DeviceRole?, val receiver: DeviceRole?)

/** Why an incoming role request is refused. */
enum class RoleRefusal { BUSY, INVALID }

/**
 * Roles are chosen after pairing, never before (D80; owner: "que se conecten dos y luego ya se reparta el rol"). The
 * stored role of a paired phone ([com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice.role]) is what that phone is
 * *for this one*: [DeviceRole.CAMERA] when this phone controls it, a controller role otherwise (also "no relation" for a
 * phone that was just paired, which is stored as [DeviceRole.MONITOR]). This phone's own role follows: a camera, or a
 * monitor of one camera, or the master of two or more. Pure, unit-tested.
 */
object SessionRoles {
    /** Stored role of a phone just paired: not a camera of this one, so nothing dials it before roles are chosen. */
    val NEUTRAL: DeviceRole = DeviceRole.MONITOR

    fun relation(own: MulticamRole?, peer: DeviceRole): Relation = when {
        own == MulticamRole.CAMERA && peer.isController -> Relation.CAMERA_OF_PEER
        own != null && own != MulticamRole.CAMERA && peer == DeviceRole.CAMERA -> Relation.CONTROLS_PEER
        else -> Relation.NONE
    }

    /** A controller of [cameras] cameras: none (off), the monitor of one, the master of several. */
    fun controllerRole(cameras: Int): MulticamRole? = when {
        cameras <= 0 -> null
        cameras == 1 -> MulticamRole.MONITOR
        else -> MulticamRole.MASTER
    }

    /** This phone picks [choice] with [peerId]; [stored] is every paired phone's stored role now. */
    fun plan(choice: RoleChoice, peerId: String, own: MulticamRole?, stored: Map<String, DeviceRole>): RolePlan = when (choice) {
        RoleChoice.CAMERA -> becomeCamera(peerId, stored, peerRole = DeviceRole.MONITOR).let {
            it.copy(sender = DeviceRole.CAMERA, receiver = DeviceRole.MONITOR)
        }
        RoleChoice.MONITOR -> controlPeer(peerId, stored).let { it.copy(sender = it.own?.toDeviceRole(), receiver = DeviceRole.CAMERA) }
        RoleChoice.END -> end(peerId, own, stored)
    }

    /**
     * [peerId] asks this phone to take [receiver] while it takes [sender]. A refusal when the pair makes no sense (two
     * cameras, a controller asking to be controlled) or when this phone records and would have to change.
     */
    fun accept(
        peerId: String,
        sender: DeviceRole?,
        receiver: DeviceRole?,
        own: MulticamRole?,
        stored: Map<String, DeviceRole>,
        recording: Boolean
    ): Result<RolePlan> {
        val plan = when {
            sender == null && receiver == null -> end(peerId, own, stored)
            receiver == DeviceRole.CAMERA && sender != null && sender.isController -> becomeCamera(peerId, stored, peerRole = sender)
            receiver != null && receiver.isController && sender == DeviceRole.CAMERA -> controlPeer(peerId, stored)
            else -> return Result.failure(RoleException(RoleRefusal.INVALID))
        }
        // A take never changes hands: a recording phone keeps its role (a camera may still be told it gained a monitor).
        if (recording && plan.own != own) return Result.failure(RoleException(RoleRefusal.BUSY))
        return Result.success(plan)
    }

    private fun becomeCamera(peerId: String, stored: Map<String, DeviceRole>, peerRole: DeviceRole): RolePlan {
        // A camera controls nobody: phones that were its cameras lose that relation.
        val changes = stored.filter { (id, role) -> id != peerId && role == DeviceRole.CAMERA }.mapValues { NEUTRAL } + (peerId to peerRole)
        return RolePlan(MulticamRole.CAMERA, changes.filter { (id, role) -> stored[id] != role }, sender = null, receiver = null)
    }

    private fun controlPeer(peerId: String, stored: Map<String, DeviceRole>): RolePlan {
        val after = stored + (peerId to DeviceRole.CAMERA)
        val own = controllerRole(after.values.count { it == DeviceRole.CAMERA })
        return RolePlan(own, if (stored[peerId] == DeviceRole.CAMERA) emptyMap() else mapOf(peerId to DeviceRole.CAMERA), sender = null, receiver = null)
    }

    private fun end(peerId: String, own: MulticamRole?, stored: Map<String, DeviceRole>): RolePlan {
        val after = stored + (peerId to NEUTRAL)
        val next = if (own == MulticamRole.CAMERA) null else controllerRole(after.values.count { it == DeviceRole.CAMERA })
        return RolePlan(next, if (stored[peerId] == NEUTRAL) emptyMap() else mapOf(peerId to NEUTRAL), sender = null, receiver = null)
    }
}

/** A refused role request ([reason]). */
class RoleException(val reason: RoleRefusal) : Exception(reason.name)
