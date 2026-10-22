package com.qtekfun.ultimatecamera.core.gpu.gl

/** How far the preview turns the naturally upright (unmirrored) camera frame to stay upright on a rotated display. */
object PreviewTurn {
    private const val FULL_TURN = 360

    /**
     * Clockwise turn in degrees for a display rotated by [displayRotation] (`Surface.ROTATION_*` × 90). The display turns
     * the screen counter-clockwise, so a back camera's picture is turned back the opposite way; a front camera looks the
     * other way, so the world turns the other way in its frame and the picture is turned with the display (D101).
     */
    fun degrees(displayRotation: Int, front: Boolean): Int {
        val rotation = (displayRotation % FULL_TURN + FULL_TURN) % FULL_TURN
        return if (front) rotation else (FULL_TURN - rotation) % FULL_TURN
    }
}
