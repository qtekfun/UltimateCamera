package com.qtekfun.ultimatecamera.feature.camera

import androidx.annotation.StringRes
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.ImagePoint
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.core.recording.PortraitLook
import com.qtekfun.ultimatecamera.core.recording.PortraitOptions
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The Portrait chip (D73): [on] for the session, the aperture slider [aperture] (0 = off, then [Bokeh.STOPS]),
 * whether the depth map is saved next to the photo ([saveDepth]), the tapped focus point in the sensor frame
 * ([focus], D87; null = the centre) and whether the shot is kept editable ([keepEdit], D88). The decisions of
 * [PortraitChip] live here, pure.
 */
data class PortraitUi(
    val on: Boolean = false,
    val aperture: Int = DEFAULT_APERTURE,
    val saveDepth: Boolean = false,
    val focus: ImagePoint? = null,
    /** Keep the unblurred photo and the depth so aperture and focus can be changed later (D88); on by default. */
    val keepEdit: Boolean = true
) {
    val fNumber: Float get() = Bokeh.fNumber(aperture)

    /** How the controller renders the next shot and the live preview. */
    val look: PortraitLook
        get() = PortraitLook(fNumber, focusX = focus?.x ?: CENTRE, focusY = focus?.y ?: CENTRE, saveDepth = saveDepth, keepEdit = keepEdit)

    /** Tapping the preview picks the focus only while our own blur runs (the maker's portrait focuses by itself). */
    fun tapFocuses(camera: PhotoState.Ready): Boolean = sliderShown(camera)

    /**
     * The controller's options: Settings › Photo › Portrait from [enabled] and Settings › Experimental from
     * [experimental] (both off when the repository does not store them), and this chip.
     */
    suspend fun options(enabled: Flow<Boolean>?, experimental: Flow<Boolean>?): PortraitOptions =
        PortraitOptions(enabled = enabled?.first() == true, experimental = experimental?.first() == true, on = on)

    /** Turning portrait on or off changes the session; the aperture and the depth PNG do not. */
    fun reopens(after: PortraitUi): Boolean = on != after.on

    /** The source the shots use: only while on and the session did open with it. */
    fun activeSource(camera: PhotoState.Ready): DepthSource? = camera.portrait?.takeIf { on }

    /** Portrait is on but its source failed on this camera: shots are normal photos and the chip says so. */
    fun unavailable(camera: PhotoState.Ready): Boolean = on && camera.portraitUnavailable

    /** The aperture slider is for our own blur; the maker's portrait (extension) is saved as it comes. */
    fun sliderShown(camera: PhotoState.Ready): Boolean = activeSource(camera)?.let { it != DepthSource.VENDOR_BOKEH } ?: false

    companion object {
        /** f/2.8. */
        const val DEFAULT_APERTURE = 3
        private const val CENTRE = 0.5f

        /** The chip is shown only for a camera that offers a depth source; none with the setting off (nothing probed). */
        fun chipShown(camera: PhotoState): Boolean = camera is PhotoState.Ready && (camera.portraitSources.isNotEmpty() || camera.portraitUnavailable)

        /** "2.8", "16" for slider [position]; null for 0 (no blur). */
        fun apertureText(position: Int): String? = if (position <= 0) null else String.format(Locale.ROOT, "%.1f", Bokeh.fNumber(position)).removeSuffix(".0")

        @StringRes
        fun sourceName(source: DepthSource): Int = when (source) {
            DepthSource.DEPTH_CAMERA -> R.string.photo_portrait_source_depth
            DepthSource.VENDOR_BOKEH -> R.string.photo_portrait_source_maker
            DepthSource.STEREO -> R.string.photo_portrait_source_stereo
        }
    }
}
