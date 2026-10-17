package com.qtekfun.ultimatecamera.core.recording

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat

/** How much the phone can take (D68): a modest phone starts with lighter defaults, never with fewer features. */
enum class PhoneTier { MODEST, CAPABLE }

/**
 * What the tier is read from: `ActivityManager.isLowRamDevice`, total RAM, the GL renderer string (empty when not known
 * yet: the first camera open reads it) and whether a hardware HEVC encoder takes 4K30.
 */
data class PhoneTraits(val lowRam: Boolean, val totalRamBytes: Long, val glRenderer: String, val encodes4k30: Boolean)

/**
 * Pure tier classifier (D68). Modest when any signal says so: a low-RAM device, less than [MODEST_RAM_BYTES], an
 * entry-level GPU ([weakGpu]) or no hardware encoder for 4K30. Conservative on purpose: a capable phone wrongly
 * called modest only gets a smaller preview and slower scopes, a modest phone wrongly called capable drops frames.
 */
object PhoneTierClassifier {
    /** 6 GiB, the same line as the proxy's modest size (D59). */
    const val MODEST_RAM_BYTES = 6L * 1024 * 1024 * 1024

    /**
     * Entry-level GPU families by `GL_RENDERER`: Mali-T*, Mali-G31/G51/G52/G57, Mali-G68 and below of the Gxx
     * generation, Adreno 3xx–5xx and Adreno 6xx below 640, PowerVR. Matched by the string, never by the brand.
     */
    private val weakGpuPatterns = listOf(
        Regex("""Mali-T\d+""", RegexOption.IGNORE_CASE),
        Regex("""Mali-G(31|51|52|57|68)\b""", RegexOption.IGNORE_CASE),
        Regex("""Adreno \(TM\) [345]\d\d\b""", RegexOption.IGNORE_CASE),
        Regex("""Adreno \(TM\) 6[0-3]\d\b""", RegexOption.IGNORE_CASE),
        Regex("""PowerVR""", RegexOption.IGNORE_CASE)
    )

    fun weakGpu(renderer: String): Boolean = renderer.isNotBlank() && weakGpuPatterns.any { it.containsMatchIn(renderer) }

    fun classify(traits: PhoneTraits): PhoneTier = when {
        traits.lowRam -> PhoneTier.MODEST
        traits.totalRamBytes in 1 until MODEST_RAM_BYTES -> PhoneTier.MODEST
        weakGpu(traits.glRenderer) -> PhoneTier.MODEST
        !traits.encodes4k30 -> PhoneTier.MODEST
        else -> PhoneTier.CAPABLE
    }
}

/** Reads [PhoneTraits] on Android (cheap: one binder call and the codec list). */
object PhoneTraitsReader {
    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160
    private const val UHD_FPS = 30.0

    fun read(context: Context, glRenderer: String): PhoneTraits {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return PhoneTraits(lowRam = am?.isLowRamDevice ?: true, totalRamBytes = info.totalMem, glRenderer = glRenderer, encodes4k30 = encodes4k30())
    }

    private fun encodes4k30(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.isHardwareAccelerated && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
                codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
                    ?.areSizeAndRateSupported(UHD_WIDTH, UHD_HEIGHT, UHD_FPS) == true
        }
    }.getOrDefault(false)
}
