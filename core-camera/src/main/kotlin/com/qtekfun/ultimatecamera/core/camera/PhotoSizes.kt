package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.abs

/** A width x height pair, independent of `android.util.Size` so the choice is testable on the JVM. */
data class PixelSize(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    val aspect: Double get() = width.toDouble() / height
}

/** The JPEG and preview sizes of the photo mode (decision D41). */
data class PhotoSizes(val jpeg: PixelSize, val preview: PixelSize) {
    companion object {
        /** Widest preview drawn through the GPU stage: enough for any phone screen, cheap on modest phones. */
        const val MAX_PREVIEW_WIDTH = 1920

        /** Aspect ratios closer than this are the same (4:3 sizes like 1440x1080 vs 4032x3024 differ by rounding). */
        const val ASPECT_TOLERANCE = 0.01

        /**
         * The largest JPEG size, and the largest preview size with its aspect ratio at most [MAX_PREVIEW_WIDTH] wide.
         * Without such a preview size, the preview with the closest aspect within the width limit is used (the
         * renderer letterboxes, so the framing is only slightly off). Null when the camera offers no JPEG output.
         */
        fun choose(jpegSizes: List<PixelSize>, previewSizes: List<PixelSize>): PhotoSizes? {
            val jpeg = jpegSizes.maxByOrNull { it.area } ?: return null
            val fitting = previewSizes.filter { it.width <= MAX_PREVIEW_WIDTH }
            val preview = fitting.filter { abs(it.aspect - jpeg.aspect) < ASPECT_TOLERANCE }.maxByOrNull { it.area }
                ?: fitting.minWithOrNull(compareBy<PixelSize> { abs(it.aspect - jpeg.aspect) }.thenByDescending { it.area })
                ?: return null
            return PhotoSizes(jpeg, preview)
        }
    }
}
