package com.qtekfun.ultimatecamera.core.capabilities

/**
 * Frame pacing variants (D104): on some phones the camera HAL leaves short gaps at 4K60, a few frames a minute, in the
 * phone's own camera app too. The HAL may pace a session better when told it records video: a vendor video operating
 * mode, a deeper buffer queue, a per-request recording flag, or the public `VIDEO_RECORD` stream use case. Each is tried
 * as a variant of the 4K60 10-bit rungs only ([plans]), and only where the camera itself lists the key family it needs:
 * matching is by key names, never by brand or model. Only modes and keys the camera offers to any app are used; nothing
 * that names another app.
 *
 * A held variant is recorded and shown, never picked for recording yet ([CapabilitySelector] ignores variants).
 */
object PacingVariants : VendorExtension {
    override val id = "pacing-v1"

    /**
     * The stock video recipe for 4K60 as a phone's own camera app requests it through the public API (D106): the
     * vendor video operating mode, video stabilization on with optical stabilization off, and the vendor recording
     * switch on every request. Keys that describe the caller (its process, its own module ids) are never sent.
     */
    const val MI_NATIVE = "mi-native"

    /** Vendor video operating mode (`0x8004`). */
    const val MI_VIDEO = "mi-video"

    /** Qualcomm realtime EIS video operating mode (`0xF004`). */
    const val QTI_EIS = "qti-eis"

    /** Vendor video mode with preview EIS (`0x8019`). */
    const val MI_VIDEO_EIS = "mi-videoeis"

    /** Vendor 60 fps high-sample-rate video mode (`0x803C`). */
    const val MI_HSR60 = "mi-hsr60"

    /** Regular session with more buffers on the Qualcomm stream queue ([QTI_EXTRA_PREVIEW_BUFFERS]). */
    const val QTI_BUFFERS = "qti-buffers"

    /** Regular session with the vendor recording flag on every request ([XIAOMI_RECORD_SWITCH] or [XIAOMI_RECORD_CONTROL]). */
    const val MI_RECORDING = "mi-recording"

    /** Regular session with the encoder's output tagged `VIDEO_RECORD` (public API, no vendor key). */
    const val RECORD_USE_CASE = "record-usecase"

    const val XIAOMI_VIDEO_SESSION = 0x8004
    const val QTI_EIS_VIDEO_SESSION = 0xF004
    const val XIAOMI_VIDEO_EIS_SESSION = 0x8019
    const val XIAOMI_HSR60_SESSION = 0x803C

    const val QTI_EXTRA_PREVIEW_BUFFERS = "org.codeaurora.qcamera3.sessionParameters.ExtraPreviewMaxBuffers"
    const val QTI_EIS_MODE = "org.codeaurora.qcamera3.sessionParameters.EISMode"
    const val XIAOMI_RECORD_CONTROL = "xiaomi.video.recordControl"
    const val XIAOMI_RECORD_SWITCH = "com.xiaomi.camera.video.recordSwitch"
    const val VIDEO_STABILIZATION = "android.control.videoStabilizationMode"
    const val OPTICAL_STABILIZATION = "android.lens.opticalStabilizationMode"

    /** `CONTROL_VIDEO_STABILIZATION_MODE_ON` and `LENS_OPTICAL_STABILIZATION_MODE_OFF`. */
    private const val ON = "1"
    private const val OFF = "0"

    /** The rate the gaps show at: the variants are planned for it alone. */
    const val TARGET_FPS = 60

    private const val EXTRA_BUFFERS = "4"

    override fun appliesTo(identity: DeviceIdentity, camera: AdvertisedCamera): Boolean = variants(camera).isNotEmpty()

    /** The variants [camera]'s keys allow, in the order they are probed. */
    override fun variants(camera: AdvertisedCamera): List<String> {
        val keys = camera.vendorRequestKeys
        val xiaomi = keys.any { it.startsWith("xiaomi.") || it.startsWith("com.xiaomi.") }
        val qti = keys.any { it.startsWith("org.codeaurora.") }
        return listOfNotNull(
            MI_NATIVE.takeIf { xiaomi },
            MI_VIDEO.takeIf { xiaomi },
            QTI_EIS.takeIf { qti },
            MI_VIDEO_EIS.takeIf { xiaomi },
            MI_HSR60.takeIf { xiaomi },
            QTI_BUFFERS.takeIf { QTI_EXTRA_PREVIEW_BUFFERS in keys },
            MI_RECORDING.takeIf { recordFlag(keys) != null },
            RECORD_USE_CASE.takeIf { camera.streamUseCase }
        )
    }

    override fun plans(camera: AdvertisedCamera, config: VideoConfig): Boolean = config.fps == TARGET_FPS

    override fun sessionParameters(camera: AdvertisedCamera, config: VideoConfig): List<VendorParameter> = when (offered(camera, config)) {
        QTI_BUFFERS -> listOfNotNull(
            VendorParameter(QTI_EXTRA_PREVIEW_BUFFERS, VendorValueType.INT, EXTRA_BUFFERS),
            VendorParameter(QTI_EIS_MODE, VendorValueType.INT, "0").takeIf { QTI_EIS_MODE in camera.vendorRequestKeys }
        )
        MI_RECORDING -> listOfNotNull(recordFlag(camera.vendorRequestKeys)?.let { VendorParameter(it, VendorValueType.INT, ON) })
        // The runner sets only keys the camera lists, so a phone without the switch or the stabilization keys gets the rest.
        MI_NATIVE -> listOfNotNull(
            VendorParameter(VIDEO_STABILIZATION, VendorValueType.INT, ON),
            VendorParameter(OPTICAL_STABILIZATION, VendorValueType.INT, OFF),
            recordFlag(camera.vendorRequestKeys)?.let { VendorParameter(it, VendorValueType.INT, ON) }
        )
        else -> emptyList()
    }

    /** The recording switch the stock app sets, or the older flag; null when the camera lists neither. */
    private fun recordFlag(keys: List<String>): String? = listOf(XIAOMI_RECORD_SWITCH, XIAOMI_RECORD_CONTROL).firstOrNull { it in keys }

    override fun sessionType(camera: AdvertisedCamera, config: VideoConfig): Int? = when (offered(camera, config)) {
        MI_VIDEO, MI_NATIVE -> XIAOMI_VIDEO_SESSION
        QTI_EIS -> QTI_EIS_VIDEO_SESSION
        MI_VIDEO_EIS -> XIAOMI_VIDEO_EIS_SESSION
        MI_HSR60 -> XIAOMI_HSR60_SESSION
        else -> null
    }

    override fun recordUseCase(camera: AdvertisedCamera, config: VideoConfig): Boolean = offered(camera, config) == RECORD_USE_CASE

    /** [config]'s variant when [camera] offers it; null for a plain configuration or a variant it does not offer. */
    private fun offered(camera: AdvertisedCamera, config: VideoConfig): String? = config.vendorMode?.takeIf { it in variants(camera) }
}
