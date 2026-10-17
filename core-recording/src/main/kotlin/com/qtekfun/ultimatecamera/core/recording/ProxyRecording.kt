package com.qtekfun.ultimatecamera.core.recording

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import com.qtekfun.ultimatecamera.core.codec.CodecCapabilityProbe
import com.qtekfun.ultimatecamera.core.codec.EncoderConfig
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.Mp4Writer
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.ProxyFrameGate
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Size, rate and bitrate of a take's proxy (D59): H.264 8-bit with the main take's aspect ratio, the long side
 * [LONG_SIDE] (720p class) or [MODEST_LONG_SIDE] on modest phones (540p class), at most [MAX_FPS] and [MAX_BITRATE].
 */
data class ProxySpec(val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) {
    val encoderConfig: EncoderConfig get() = EncoderConfig(VideoCodec.H264, width, height, fps, bitrateBps, tenBit = false)

    companion object {
        const val LONG_SIDE = 1280
        const val MODEST_LONG_SIDE = 960
        const val MAX_FPS = 30
        const val MAX_BITRATE = 10_000_000
        const val MIN_BITRATE = 2_000_000

        /** H.264 bits per pixel that keep a 720p proxy clean for editing and review. */
        private const val BITS_PER_PIXEL = 0.25

        /** RAM below this (or a low-RAM device) is a modest phone: smaller proxy, less encoder and memory load. */
        private const val MODEST_RAM_BYTES = 6L * 1024 * 1024 * 1024

        fun forMain(mainWidth: Int, mainHeight: Int, mainFps: Int, modest: Boolean): ProxySpec {
            val long = if (modest) MODEST_LONG_SIDE else LONG_SIDE
            val landscape = mainWidth >= mainHeight
            val mainLong = maxOf(mainWidth, mainHeight)
            val mainShort = minOf(mainWidth, mainHeight)
            val proxyLong = minOf(long, even(mainLong))
            // Even sizes: H.264 encoders want multiples of 2 (4:2:0 chroma).
            val proxyShort = even((proxyLong.toLong() * mainShort / mainLong).toInt())
            val fps = minOf(mainFps, MAX_FPS)
            val bitrate = (proxyLong.toLong() * proxyShort * fps * BITS_PER_PIXEL).toLong().coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
            return if (landscape) ProxySpec(proxyLong, proxyShort, fps, bitrate) else ProxySpec(proxyShort, proxyLong, fps, bitrate)
        }

        /** A low-RAM device or one with less than 6 GiB. */
        fun isModestPhone(context: Context): Boolean {
            val am = context.getSystemService(ActivityManager::class.java) ?: return true
            val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            return am.isLowRamDevice || info.totalMem < MODEST_RAM_BYTES
        }

        private fun even(value: Int): Int = maxOf(2, value - value % 2)
    }
}

/**
 * Copies a take's encoded AAC into the proxy file too, so the proxy has the same audio without a second encoder (the
 * cheaper choice, D59). The first format wins: when the take is split into parts the audio recorder is recreated, and
 * the proxy keeps one track across parts. Called from the audio thread.
 */
class AudioMirror(private val writer: Mp4Writer) {
    @Volatile private var track: Mp4Writer.Track? = null
    private var added = false

    fun onFormat(format: MediaFormat) = synchronized(this) {
        if (added) return
        added = true
        track = runCatching { writer.addTrack(format) }.getOrNull()
    }

    fun write(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        track?.write(buffer, info)
    }
}

/** A finished proxy on this phone, for the upload server (REQ-NET-006). */
data class ProxyFile(val name: String, val uri: String, val bytes: Long)

/** The proxies recorded since the app started, newest last; read by the proxy upload server (D59). */
object ProxyRegistry {
    private val files = CopyOnWriteArrayList<ProxyFile>()

    val all: List<ProxyFile> get() = files.toList()

    fun add(file: ProxyFile) {
        files.removeAll { it.name == file.name }
        files += file
    }
}

/**
 * The proxy part of a take (D59): its own file, muxer and H.264 encoder fed by the renderer after the main encoder,
 * plus the [mirror] of the take's AAC. Failures here never end the take: the proxy is simply dropped.
 */
internal class ProxyTake(
    private val file: OutputFile,
    private val name: String,
    private val writer: Mp4Writer,
    val video: SurfaceVideoEncoder,
    val mirror: AudioMirror?,
    val spec: ProxySpec,
    val gate: ProxyFrameGate
) {
    /** Ends the encoder and the file; keeps it when something was written. Returns its problem, or null. */
    fun finish(): String? {
        video.stop()
        writer.stop()
        val usable = writer.isStarted && video.encodedFrames.get() > 0
        if (usable) {
            runCatching { file.sync() }
            // The file's real size (container and audio included), read before the descriptor closes.
            val size = runCatching { android.system.Os.fstat(file.fileDescriptor).st_size }.getOrDefault(video.encodedBytes.get())
            runCatching { file.close() }
            ProxyRegistry.add(ProxyFile(name, file.uri, size))
        } else {
            runCatching { file.delete() }
        }
        return listOfNotNull(video.error, writer.error).firstOrNull()?.let { "proxy: $it" } ?: if (usable) null else "proxy: nothing was recorded"
    }

    companion object {
        /** The proxy of a take of [profile] into [output], with a hardware-first H.264 encoder from [encoders]; null when none. */
        fun forTake(
            output: VideoOutput,
            profile: RecordingProfile,
            encoders: List<EncoderInfo>,
            modest: Boolean,
            withAudio: Boolean,
            orientationHint: Int
        ): ProxyTake? {
            val spec = ProxySpec.forMain(profile.width, profile.height, profile.fps, modest)
            val info = CodecCapabilityProbe.pick(encoders, spec.encoderConfig) ?: return null
            return open(output, spec, profile.fps, info.name, withAudio, orientationHint)
        }

        /**
         * Opens the proxy of a take whose output is [output]; null (no proxy, the take goes on) when the output cannot
         * hold one or the encoder cannot be created.
         */
        @Suppress("TooGenericExceptionCaught") // Vendor codecs throw anything; a proxy failure must not cost the take.
        fun open(output: VideoOutput, spec: ProxySpec, sourceFps: Int, codecName: String, withAudio: Boolean, orientationHint: Int): ProxyTake? {
            val file = try {
                output.openProxy()
            } catch (_: java.io.IOException) {
                null
            } ?: return null
            return try {
                val writer = Mp4Writer(file.fileDescriptor, expectedTracks = if (withAudio) 2 else 1, orientationHint = orientationHint)
                val video = SurfaceVideoEncoder(spec.encoderConfig, codecName, writer, ownsWriter = false)
                ProxyTake(
                    file,
                    output.proxyName ?: "proxy.mp4",
                    writer,
                    video,
                    if (withAudio) AudioMirror(writer) else null,
                    spec,
                    ProxyFrameGate(sourceFps, spec.fps)
                )
            } catch (_: Exception) {
                runCatching { file.delete() }
                null
            }
        }
    }
}
