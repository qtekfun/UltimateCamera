package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile

/**
 * The recording modes the camera screen offers, best first (REQ-NFR-001 decision D38: 4K60 10-bit where the phone
 * holds it, 4K30 10-bit as the floor, then 8-bit and lower sizes). When the HAL or the encoders refuse a mode at open,
 * the screen moves to the next one, so every phone ends on the best mode it really configures.
 *
 * 8K is experimental (D37) and only offered when [experimental] is on.
 */
object ProfileLadder {
    private val UHD = 3840 to 2160
    private val FHD = 1920 to 1080
    private val UHD8K = 7680 to 4320

    /** H.264 is offered 8-bit only: phones practically never encode H.264 High 10 in hardware. */
    fun profiles(experimental: Boolean = false, codec: VideoCodec = VideoCodec.HEVC): List<RecordingProfile> = buildList {
        // Smooth motion before bit depth (D105): 60 fps in 8-bit comes before 30 fps in 10-bit at each size.
        add(profile(UHD, 60, tenBit = true))
        add(profile(UHD, 60, tenBit = false))
        add(profile(UHD, 30, tenBit = true))
        add(profile(UHD, 30, tenBit = false))
        add(profile(FHD, 60, tenBit = true))
        add(profile(FHD, 60, tenBit = false))
        add(profile(FHD, 30, tenBit = true))
        add(profile(FHD, 30, tenBit = false))
        if (experimental && codec != VideoCodec.H264) {
            add(profile(UHD8K, 30, tenBit = false))
            add(profile(UHD8K, 60, tenBit = false))
        }
    }.filter { codec != VideoCodec.H264 || !it.tenBit }
        .map { it.copy(codec = codec, bitrateBps = RecordingProfile.defaultBitrate(it.width, it.height, it.fps, it.tenBit, codec)) }

    /** The mode to try after [failed] was refused: the next one down that is not in [refused]; null at the bottom. */
    fun next(failed: RecordingProfile, refused: Set<RecordingProfile>, ladder: List<RecordingProfile>): RecordingProfile? {
        val from = ladder.indexOf(failed)
        return ladder.drop(from + 1).firstOrNull { it !in refused && !isExperimental(it) }
    }

    fun isExperimental(profile: RecordingProfile): Boolean = profile.height > UHD.second

    private fun profile(size: Pair<Int, Int>, fps: Int, tenBit: Boolean) = RecordingProfile(size.first, size.second, fps, VideoCodec.HEVC, tenBit)
}
