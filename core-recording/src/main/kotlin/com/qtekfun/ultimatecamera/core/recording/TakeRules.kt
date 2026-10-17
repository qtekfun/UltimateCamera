package com.qtekfun.ultimatecamera.core.recording

/** The decisions of [CameraController] that need no camera, GL or codec: timing, orientation and a part's outcome. */
internal object TakeRules {
    private const val FULL_TURN = 360
    private const val NANOS_PER_MS = 1_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /**
     * Rotation in degrees that makes a camera's frames upright on a display rotated by [displayRotationDegrees]: front
     * cameras turn with the display, back cameras against it.
     */
    fun uprightRotation(sensorOrientation: Int, front: Boolean, displayRotationDegrees: Int): Int =
        if (front) (sensorOrientation + displayRotationDegrees) % FULL_TURN else (sensorOrientation - displayRotationDegrees + FULL_TURN) % FULL_TURN

    /** Clockwise turn from the sensor frame to the preview on screen: the sensor's turn minus the display compensation. */
    fun previewRotationDegrees(sensorOrientation: Int, previewRotation: Int): Int = (sensorOrientation - previewRotation + FULL_TURN) % FULL_TURN

    /** A touch at [x] on the shown preview, in the unmirrored picture: the front preview is mirrored left-right. */
    fun unmirroredX(x: Float, viewWidth: Int, mirrored: Boolean): Float = if (mirrored) viewWidth - x else x

    /** The take's first frame instant: the scheduled one ([atCameraNanos]), or [now] when none or already past. */
    fun startInstant(now: Long, atCameraNanos: Long?): Long = maxOf(now, atCameraNanos ?: now)

    /** How late a scheduled start is in nanoseconds (0 on time or unscheduled). */
    fun lateness(now: Long, atCameraNanos: Long?): Long = atCameraNanos?.let { (now - it).coerceAtLeast(0) } ?: 0L

    /** The running-time origin on the elapsed-realtime clock: a scheduled take counts from its start instant. */
    fun startedAtElapsedMs(elapsedNowMs: Long, start: Long, now: Long): Long = elapsedNowMs + (start - now) / NANOS_PER_MS

    /** A part is worth keeping once the muxer started and at least one video frame was encoded. */
    fun usable(writerStarted: Boolean, encodedFrames: Int): Boolean = writerStarted && encodedFrames > 0

    /**
     * A finished part's problem (null when clean): the first error in video, audio, muxer, WAV order, else "nothing was
     * recorded" for a part that is not [usable].
     */
    fun partProblem(videoError: String?, audioError: String?, writerError: String?, wavError: String?, usable: Boolean): String? =
        listOfNotNull(videoError, audioError, writerError, wavError?.let { "WAV: $it" }).firstOrNull() ?: if (usable) null else "nothing was recorded"

    /**
     * Frames of silence the take's WAV needs between the end of the previous part's audio ([tapEndNanos], -1 unknown)
     * and the next part's first sample ([resumeNanos]), so the microphone handover does not pull the rest of the WAV
     * earlier than the video (D83). Zero when unknown or overlapping.
     */
    fun handoverFrames(tapEndNanos: Long, resumeNanos: Long, sampleRate: Int): Long =
        if (tapEndNanos < 0 || resumeNanos <= tapEndNanos) 0 else ((resumeNanos - tapEndNanos) * sampleRate + NANOS_PER_SECOND / 2) / NANOS_PER_SECOND

    /** The take's problem: the take's own first, the proxy's only when the take is clean. */
    fun takeProblem(mainProblem: String?, proxyProblem: String?): String? = mainProblem ?: proxyProblem
}
