package com.qtekfun.ultimatecamera.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.feature.multicam.session.MulticamSession
import com.qtekfun.ultimatecamera.feature.multicam.session.RoleAssignment

/** Where the app goes when the other phone of a multicamera pair gives this one a role (D80). Pure, unit-tested. */
object MulticamSessionNavigation {
    /**
     * The camera screen for a camera (its role runs there, so the monitor gets status and video); the multicamera
     * screen (which opens its control page) for a monitor or master; nothing when the session ended or the choice was
     * made on this phone (its multicamera screen moves on by itself).
     */
    fun destination(assignment: RoleAssignment): Destination? = when {
        !assignment.fromPeer -> null
        assignment.own == MulticamRole.CAMERA -> Destination.CAMERA
        assignment.own == MulticamRole.MONITOR || assignment.own == MulticamRole.MASTER -> Destination.MULTICAM
        else -> null
    }
}

/** Runs the multicamera session socket while the activity is started (foreground only). */
class SessionLifecycle(private val session: MulticamSession, private val scope: LifecycleCoroutineScope) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = session.start(scope)

    override fun onStop(owner: LifecycleOwner) = session.stop()
}
