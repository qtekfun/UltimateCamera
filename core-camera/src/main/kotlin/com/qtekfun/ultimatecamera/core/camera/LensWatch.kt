package com.qtekfun.ultimatecamera.core.camera

/**
 * The physical lens behind a logical camera, from each result's `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (D113). The
 * HAL switches lenses with the zoom ratio; the white balance copied from one lens (its colour matrix and gains) is
 * wrong on another, so a switch makes the stream copy them again ([CameraStream]). Not thread-safe: camera thread only.
 */
class LensWatch {
    /** The lens of the last result that named one; null before any. */
    var lens: String? = null
        private set

    /** True when [reported] names another lens than the last one named; the first lens named is not a switch. */
    fun switched(reported: String?): Boolean {
        if (reported == null || reported == lens) return false
        val before = lens
        lens = reported
        return before != null
    }
}

/**
 * When to copy the camera's automatic white balance (colour matrix and gains) for the scene white balance (D113). At
 * the start of a stream any automatic result will do, as before; after a lens switch the camera's AWB first needs to
 * settle on the new lens: it is copied once the camera reports it converged, or after [SETTLE_FRAMES] results at most
 * (some HALs never report convergence).
 */
class AwbLatch {
    private var waiting = 0

    /** The camera's AWB starts again (a lens switch): wait for it to settle before copying. */
    fun restart() {
        waiting = SETTLE_FRAMES
    }

    /** One automatic-white-balance result; true when its matrix and gains should be copied. */
    fun copies(converged: Boolean): Boolean {
        if (waiting == 0) return true
        waiting = if (converged) 0 else waiting - 1
        return waiting == 0
    }

    /** Waiting for the camera's AWB to settle: the scene white balance holds off. */
    val settling: Boolean get() = waiting > 0

    companion object {
        /** At most this many results (half a second at 60 fps) before copying anyway. */
        const val SETTLE_FRAMES = 30
    }
}
