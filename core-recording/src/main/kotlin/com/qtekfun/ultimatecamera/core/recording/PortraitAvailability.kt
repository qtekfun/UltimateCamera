package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.camera.DepthSource

/**
 * Depth sources that failed on a camera in this run (D73): the session was refused, or a shot came back without its
 * depth (the HAL streams the physical cameras on paper only). Such a source is not offered again for that camera, so
 * the user never waits on it twice; the next shot is a normal photo. Kept for the process (a HAL or firmware update
 * may fix it).
 */
class PortraitAvailability {
    private val failed = HashMap<String, MutableSet<DepthSource>>()

    /** [sources] without the ones that failed on [cameraId]. */
    fun usable(cameraId: String, sources: List<DepthSource>): List<DepthSource> {
        val bad = failed[cameraId] ?: return sources
        return sources.filterNot { it in bad }
    }

    fun markFailed(cameraId: String, source: DepthSource) {
        failed.getOrPut(cameraId) { HashSet() } += source
    }

    fun failed(cameraId: String, source: DepthSource): Boolean = failed[cameraId]?.contains(source) == true
}
