package com.qtekfun.ultimatecamera.core.recording

import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.capabilities.DirectMode
import com.qtekfun.ultimatecamera.core.codec.ColorTag
import com.qtekfun.ultimatecamera.core.codec.Mp4Writer
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import kotlinx.coroutines.delay

/**
 * The encoder side of the experimental direct mode (D71) for one open camera: a persistent input surface that the
 * camera session outputs into (only while a take records, see `CameraStream.setRecording`) and a new encoder on it per
 * take. No GL pass: no LUT, no UC-Log, no proxy, no monitor stream from the encoder; the file is tagged as the camera
 * delivers (HLG for 10-bit). The viewfinder gets the session's second, ≤1920-wide stream through the renderer.
 */
internal class DirectPath private constructor(private val profile: RecordingProfile, private val codecName: String, val surface: Surface) {
    /** A take's encoder on the persistent surface, writing into [writer]. */
    fun encoder(writer: Mp4Writer): SurfaceVideoEncoder =
        SurfaceVideoEncoder(profile.encoderConfig.copy(colorTag = colorTag(profile)), codecName, writer, ownsWriter = false, persistentSurface = surface)

    /** The take's colour tag ([colorTag]). */
    val tag: ColorTag? get() = colorTag(profile)

    /** Starts sending frames to the encoder in [delayNanos] (no frame gate without the GL pass, D58 precision ≈ a frame). */
    suspend fun start(stream: CameraStream?, delayNanos: Long) {
        delay(delayNanos / NANOS_PER_MS)
        stream?.setRecording(true)
    }

    /** After the camera stream is closed. */
    fun release() = surface.release()

    companion object {
        fun open(profile: RecordingProfile, codecName: String): DirectPath =
            DirectPath(profile, codecName, SurfaceVideoEncoder.primePersistentSurface(profile.encoderConfig.copy(colorTag = colorTag(profile)), codecName))

        /** The viewfinder stream's size next to [profile]'s recording. */
        fun previewSize(profile: RecordingProfile): Pair<Int, Int> = DirectMode.previewSize(profile.width, profile.height)

        /** As the camera delivers: HLG BT.2020 for a 10-bit stream, the encoder's default for 8-bit. */
        fun colorTag(profile: RecordingProfile): ColorTag? = if (profile.tenBit) ColorTag.HLG_BT2020 else null

        /** Frames settle this long after the camera stops targeting the encoder, before its end of stream. */
        const val STOP_SETTLE_MS = 100L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
