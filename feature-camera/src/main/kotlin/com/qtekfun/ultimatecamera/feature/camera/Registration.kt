package com.qtekfun.ultimatecamera.feature.camera

/**
 * A listener that is registered at most once and unregistered only if registered. Platform registries such as
 * `PowerManager.removeThermalStatusListener` throw when asked to remove a listener they do not hold; the camera
 * screen's monitor called stop without a start (leaving the screen in photo mode, or onStop twice) and crashed.
 */
class Registration(private val add: () -> Unit, private val remove: () -> Unit) {
    var registered = false
        private set

    fun register() {
        if (registered) return
        add()
        registered = true
    }

    fun unregister() {
        if (!registered) return
        registered = false
        remove()
    }
}
