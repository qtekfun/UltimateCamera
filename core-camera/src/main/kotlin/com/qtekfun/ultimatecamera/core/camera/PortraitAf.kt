package com.qtekfun.ultimatecamera.core.camera

/**
 * The lens autofocus at the portrait focus point (D88): a tap that sets the blur's focus also moves the camera's AF
 * (and AE) there. The point goes through the same [CameraControls.meteringPoint] path as the video controls: the
 * region is [ControlMath.imageToSensor] of the point on the still's frame, and the one-off trigger is
 * [CameraStream.afTrigger] (CANCEL on a new point: continuous AF scans again on the new region).
 */
object PortraitAf {
    /** The regions to put on every request of the portrait session and the trigger to send once (null = none). */
    data class Plan(val afRegion: SensorRect?, val aeRegion: SensorRect?, val trigger: Int?)

    /**
     * The plan for a change of the focus point from [previous] to [next] (sensor frame, normalised; null = the whole
     * frame, as the camera decides) on a camera with [ranges] taking stills of [still]'s aspect. No AF region on a
     * fixed-focus lens or a camera without AF regions; no AE region without AE regions.
     */
    fun plan(previous: ImagePoint?, next: ImagePoint?, ranges: ControlRanges, still: PixelSize): Plan {
        val before = CameraControls(meteringPoint = previous?.clamped())
        val after = CameraControls(meteringPoint = next?.clamped())
        val region = after.meteringPoint?.let { ControlMath.imageToSensor(it, ranges.activeArray, still.width, still.height) }
        val autofocus = ranges.minFocusDiopters > 0f
        return Plan(
            afRegion = region?.takeIf { autofocus && ranges.maxAfRegions > 0 },
            aeRegion = region?.takeIf { ranges.maxAeRegions > 0 },
            trigger = CameraStream.afTrigger(before, after, ranges)
        )
    }

    private fun ImagePoint.clamped() = ImagePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}
