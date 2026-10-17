package com.qtekfun.ultimatecamera.core.recording

import android.media.ExifInterface
import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.CpuPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.Depth16
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthImage
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.StereoDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.reduced

/** The pure steps of [PortraitProcessor] (D73): depth from the shot, the focus, the CoC edge mask, EXIF turn, PNG grey. */
internal object PortraitMaps {
    private const val HALF = 0.5f
    private const val MAX_BYTE = 255f
    private const val STEREO_REDUCTION = 2
    private const val MAX_DISPARITY_FRACTION = 0.05f
    private const val LIVE_STEREO_REDUCTION = 4
    private const val FULL_TURN = 360

    /** True where the reduced CoC changes by more than a pixel within its 3 x 3 neighbourhood. */
    fun edgeMask(coc: Plane): BooleanArray {
        val mask = BooleanArray(coc.width * coc.height)
        for (y in 0 until coc.height) {
            for (x in 0 until coc.width) mask[y * coc.width + x] = spread(coc, x, y) > 1f
        }
        return mask
    }

    /** Largest minus smallest value of the 3 x 3 neighbourhood of ([x], [y]), clamped at the border. */
    fun spread(coc: Plane, x: Int, y: Int): Float {
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val v = coc[(x + dx).coerceIn(0, coc.width - 1), (y + dy).coerceIn(0, coc.height - 1)]
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
        }
        return hi - lo
    }

    /** The focus of [look]'s point (the tapped one, or the centre) on [depth]: [Bokeh.focusAt]. */
    fun focus(depth: Plane, look: PortraitLook): Float = Bokeh.focusAt(depth, look.focusX, look.focusY)

    /** Inverse depth of a shot's [frame]; stereo block matching runs on [kernels] at 1/[stereoReduction] of the streams. */
    fun inverseDepth(frame: DepthFrame, kernels: PortraitKernels = CpuPortraitKernels, stereoReduction: Int = STEREO_REDUCTION): Plane = when (frame) {
        is DepthFrame.Depth16 -> Depth16.inverseDepth(frame.samples, frame.width, frame.height, frame.rowStride)
        is DepthFrame.Stereo -> stereoDepth(frame, kernels, stereoReduction)
    }

    /**
     * One update of the live portrait preview (D83) from a live depth [frame]: the stereo pair is matched at a quarter of
     * its size (a few milliseconds on the CPU, cheaper than a GPU round trip at that size), the focus is [look]'s point.
     */
    fun liveDepth(frame: DepthFrame, look: PortraitLook): LiveDepth {
        val inverse = inverseDepth(frame, CpuPortraitKernels, LIVE_STEREO_REDUCTION)
        return LiveDepth(inverse, focus(inverse, look), look.fNumber)
    }

    /** Main luma and the second camera's centre crop (its view matched to the main's), both reduced, block-matched. */
    fun stereoDepth(frame: DepthFrame.Stereo, kernels: PortraitKernels = CpuPortraitKernels, reduction: Int = STEREO_REDUCTION): Plane {
        val w = (frame.width / reduction).coerceAtLeast(1)
        val h = (frame.height / reduction).coerceAtLeast(1)
        val main = Plane(frame.width, frame.height) { i -> (frame.main[i].toInt() and 0xFF) / MAX_BYTE }.reduced(w, h)
        val crop = frame.pair.secondCrop
        // Cropped at full size, then reduced like the main, so both get the same filtering.
        val fw = frame.width
        val fh = frame.height
        val second = Plane(fw, fh) { i ->
            val sx = ((HALF - crop / 2f + crop * (i % fw + HALF) / fw) * fw).toInt().coerceIn(0, fw - 1)
            val sy = ((HALF - crop / 2f + crop * (i / fw + HALF) / fh) * fh).toInt().coerceIn(0, fh - 1)
            (frame.second[sy * fw + sx].toInt() and 0xFF) / MAX_BYTE
        }.reduced(w, h)
        val maxDisparity = (w * MAX_DISPARITY_FRACTION).toInt().coerceAtLeast(2)
        return StereoDepth.inverseDepth(StereoDepth.median3(kernels.disparity(main, second, maxDisparity, PortraitKernels.DEFAULT_BLOCK_RADIUS)), maxDisparity)
    }

    /** Opaque grey ARGB pixels of the depth map (white near), for the PNG. */
    fun greyArgb(depth: Plane): IntArray {
        val grey = DepthImage.grey(depth)
        return IntArray(grey.size) { i ->
            val v = grey[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** EXIF orientation for a clockwise turn of [degrees] (0, 90, 180, 270). */
    fun exifOrientation(degrees: Int): Int = when (((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun Plane(width: Int, height: Int, value: (Int) -> Float): Plane = Plane(width, height, FloatArray(width * height, value))
}
