package com.qtekfun.ultimatecamera.core.camera

/**
 * How a photo gets its high dynamic range (decision D64). [key] is persisted and exported: never rename it.
 * The extension methods are the phone maker's own processing through Camera2 Extensions; [ULTRA_HDR] is the camera's
 * JPEG_R output (a JPEG with a gain map); [FUSION] is our own three-exposure bracket merged with exposure fusion.
 */
enum class HdrMethod(val key: String, val extension: Boolean = false) {
    EXTENSION_HDR("extension_hdr", extension = true),
    EXTENSION_AUTO("extension_auto", extension = true),
    EXTENSION_NIGHT("extension_night", extension = true),
    ULTRA_HDR("ultra_hdr"),
    FUSION("fusion"),
    OFF("off")
}

/**
 * What the camera really offers for HDR photos, read from Camera2 (never assumed per brand).
 * [extensions]: extension methods listed by `CameraExtensionCharacteristics.getSupportedExtensions()` with a JPEG size;
 * [extensionJpegR]: those that also list JPEG_R sizes; [jpegR]: JPEG_R in the stream configuration map;
 * [bracket]: a YUV still output plus manual exposure or AE compensation, enough for our own bracket.
 */
data class HdrSupport(
    val extensions: Set<HdrMethod> = emptySet(),
    val extensionJpegR: Set<HdrMethod> = emptySet(),
    val jpegR: Boolean = false,
    val bracket: Boolean = false
) {
    /** The labels written to the capability catalogue and the diagnostics export, e.g. `extension_hdr`, `jpeg_r`. */
    fun labels(): List<String> = buildList {
        HdrMethod.entries.filter { it in extensions }.forEach { add(it.key) }
        HdrMethod.entries.filter { it in extensionJpegR }.forEach { add("${it.key}+jpeg_r") }
        if (jpegR) add("jpeg_r")
        if (bracket) add("bracket")
    }
}

/** The HDR choice of the photo chip: automatic (the best the phone offers), off, or one method picked by hand. */
sealed interface HdrChoice {
    data object Auto : HdrChoice

    data object Off : HdrChoice

    data class Manual(val method: HdrMethod) : HdrChoice
}

/** What a shot will do: the [method] and whether the saved JPEG carries an Ultra HDR gain map ([ultraHdr]). */
data class HdrPlan(val method: HdrMethod, val ultraHdr: Boolean)

/** Picks the HDR method from [HdrSupport] (pure, so every phone class is unit-tested). */
object HdrSelector {
    /** Automatic order of preference: the maker's HDR, then the camera's JPEG_R, then our own fusion. */
    private val AUTO_ORDER = listOf(HdrMethod.EXTENSION_HDR, HdrMethod.ULTRA_HDR, HdrMethod.FUSION)

    /** Every method this camera can run, in display order (OFF last, always present). */
    fun available(support: HdrSupport): List<HdrMethod> = HdrMethod.entries.filter { offered(support, it) }

    /** The plan for [choice]; a manual method the camera lost (other lens) falls back to automatic. */
    fun plan(support: HdrSupport, choice: HdrChoice, gainMapEncoder: Boolean): HdrPlan {
        val method = when (choice) {
            HdrChoice.Off -> HdrMethod.OFF
            is HdrChoice.Manual -> choice.method.takeIf { offered(support, it) } ?: auto(support)
            HdrChoice.Auto -> auto(support)
        }
        val ultra = when {
            method == HdrMethod.ULTRA_HDR -> true
            method.extension -> method in support.extensionJpegR
            method == HdrMethod.FUSION -> gainMapEncoder
            else -> false
        }
        return HdrPlan(method, ultra)
    }

    private fun auto(support: HdrSupport): HdrMethod = AUTO_ORDER.firstOrNull { offered(support, it) } ?: HdrMethod.OFF

    private fun offered(support: HdrSupport, method: HdrMethod): Boolean = when {
        method == HdrMethod.OFF -> true
        method.extension -> method in support.extensions
        method == HdrMethod.ULTRA_HDR -> support.jpegR
        else -> support.bracket
    }
}
