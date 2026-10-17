package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** White-balance gains for the four Bayer channels (`COLOR_CORRECTION_GAINS` order R, Geven, Godd, B). */
data class RggbGains(val red: Float, val greenEven: Float, val greenOdd: Float, val blue: Float)

/**
 * A camera's colour calibration for one reference illuminant: `SENSOR_COLOR_TRANSFORMn` (CIE XYZ → reference sensor
 * colour space) and `SENSOR_CALIBRATION_TRANSFORMn` (reference → this unit), both 3×3 row-major, at [kelvin].
 */
data class ColorCalibration(val kelvin: Int, val colorMatrix: FloatArray, val calibration: FloatArray = IDENTITY) {
    override fun equals(other: Any?): Boolean =
        other is ColorCalibration && kelvin == other.kelvin && colorMatrix.contentEquals(other.colorMatrix) && calibration.contentEquals(other.calibration)

    override fun hashCode(): Int = (kelvin * 31 + colorMatrix.contentHashCode()) * 31 + calibration.contentHashCode()

    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

/** The pure maths behind the manual controls: shutter rules, preview → sensor coordinates and Kelvin → gains. */
object ControlMath {
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** One frame at [fps], in nanoseconds (also the `SENSOR_FRAME_DURATION` for manual exposure). */
    fun frameDurationNs(fps: Int): Long = (NANOS_PER_SECOND.toDouble() / fps).roundToLong()

    /** The 180° shutter rule: exposure of half a frame, 1/(2·fps) (1/50 at 25 fps, 1/48 at 24, 1/120 at 60). */
    fun shutter180Ns(fps: Int): Long = (NANOS_PER_SECOND.toDouble() / (2 * fps)).roundToLong()

    /**
     * The picture's rectangle inside a [viewWidth]×[viewHeight] preview: the largest one with the video's aspect,
     * centred, as `CameraRenderer` letterboxes it (keep both in step). Returns x, y, width, height in view pixels.
     */
    fun fitViewport(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int): IntArray {
        val scale = minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val w = (videoWidth * scale).toInt()
        val h = (videoHeight * scale).toInt()
        return intArrayOf((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
    }

    /**
     * Where a touch at ([x], [y]) on the preview view lands on the recorded picture, or null in the letterbox bars.
     * [rotation] is the clockwise turn (0, 90, 180, 270) the renderer gives the picture on screen; at 90 and 270 the
     * letterbox fits the turned (portrait) picture.
     */
    fun previewToImage(x: Float, y: Float, viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int, rotation: Int): ImagePoint? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val box = shownPicture(viewWidth, viewHeight, videoWidth, videoHeight, rotation)
        val u = (x - box[0]) / box[2]
        val v = (y - box[1]) / box[3]
        if (u !in 0f..1f || v !in 0f..1f) return null
        // Undo the clockwise turn: screen (u, v) back to picture (x, y).
        return when (normalise(rotation)) {
            90 -> ImagePoint(v, 1f - u)
            180 -> ImagePoint(1f - u, 1f - v)
            270 -> ImagePoint(1f - v, u)
            else -> ImagePoint(u, v)
        }
    }

    /** Inverse of [previewToImage]: the view position of [point] (for drawing the focus square). */
    fun imageToPreview(point: ImagePoint, viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int, rotation: Int): Pair<Float, Float> {
        val box = shownPicture(viewWidth, viewHeight, videoWidth, videoHeight, rotation)
        val (u, v) = when (normalise(rotation)) {
            90 -> Pair(1f - point.y, point.x)
            180 -> Pair(1f - point.x, 1f - point.y)
            270 -> Pair(point.y, 1f - point.x)
            else -> Pair(point.x, point.y)
        }
        return Pair(box[0] + u * box[2], box[1] + v * box[3])
    }

    /** The picture's rectangle on screen; at quarter turns it is the portrait (turned) picture that is fitted. */
    private fun shownPicture(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int, rotation: Int): IntArray {
        val quarter = normalise(rotation) % 180 == 90
        return fitViewport(viewWidth, viewHeight, if (quarter) videoHeight else videoWidth, if (quarter) videoWidth else videoHeight)
    }

    private fun normalise(rotation: Int): Int = ((rotation % 360) + 360) % 360

    /**
     * The metering rectangle (active-array pixels) around [point] of a [videoWidth]×[videoHeight] stream read from
     * [crop] (the `SCALER_CROP_REGION`, the whole active array without zoom). The stream is the centred part of the crop
     * with the video's aspect; the square side is [sizeFraction] of the stream's shorter side, kept inside the crop.
     */
    fun imageToSensor(point: ImagePoint, crop: SensorRect, videoWidth: Int, videoHeight: Int, sizeFraction: Float = 0.15f): SensorRect {
        val videoAspect = videoWidth.toFloat() / videoHeight
        val cropAspect = crop.width.toFloat() / crop.height
        val streamW = if (cropAspect > videoAspect) crop.height * videoAspect else crop.width.toFloat()
        val streamH = if (cropAspect > videoAspect) crop.height.toFloat() else crop.width / videoAspect
        val streamLeft = crop.left + (crop.width - streamW) / 2
        val streamTop = crop.top + (crop.height - streamH) / 2
        val side = (minOf(streamW, streamH) * sizeFraction).roundToInt().coerceIn(1, minOf(crop.width, crop.height))
        val cx = streamLeft + point.x.coerceIn(0f, 1f) * streamW
        val cy = streamTop + point.y.coerceIn(0f, 1f) * streamH
        val left = (cx - side / 2f).roundToInt().coerceIn(crop.left, crop.left + crop.width - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(crop.top, crop.top + crop.height - side)
        return SensorRect(left, top, side, side)
    }
}

/** Colour temperature → white-balance gains for manual Kelvin. */
object KelvinMath {
    /** CIE 1931 xy of a black body at [kelvin] (Kim et al. 2002 cubic fit of the Planckian locus, 1667–25000 K). */
    fun planckianXy(kelvin: Int): Pair<Double, Double> {
        val t = kelvin.coerceIn(1667, 25000).toDouble()
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t <= 2222 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        return Pair(x, y)
    }

    /**
     * White-balance gains that make a grey lit at [kelvin] neutral, DNG style: the XYZ of the black body is taken to the
     * sensor's raw space through the calibration matrices (interpolated between the two reference illuminants by inverse
     * temperature), and each channel's gain is the inverse of its response, green normalised to 1.
     */
    fun kelvinToGains(kelvin: Int, first: ColorCalibration, second: ColorCalibration?): RggbGains {
        val matrix = interpolatedMatrix(kelvin, first, second)
        val (x, y) = planckianXy(kelvin)
        val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val neutral = DoubleArray(3) { row -> (0 until 3).sumOf { col -> matrix[row * 3 + col] * xyz[col] } }
        val g = neutral[1]
        val red = (g / neutral[0]).toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
        val blue = (g / neutral[2]).toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
        return RggbGains(red, 1f, 1f, blue)
    }

    private fun interpolatedMatrix(kelvin: Int, first: ColorCalibration, second: ColorCalibration?): DoubleArray {
        val m1 = multiply(first.calibration, first.colorMatrix)
        if (second == null || second.kelvin == first.kelvin) return m1
        val m2 = multiply(second.calibration, second.colorMatrix)
        val inv = 1.0 / kelvin
        val inv1 = 1.0 / first.kelvin
        val inv2 = 1.0 / second.kelvin
        val w = ((inv - inv2) / (inv1 - inv2)).coerceIn(0.0, 1.0)
        return DoubleArray(9) { w * m1[it] + (1 - w) * m2[it] }
    }

    private fun multiply(a: FloatArray, b: FloatArray): DoubleArray =
        DoubleArray(9) { i -> (0 until 3).sumOf { k -> a[i / 3 * 3 + k].toDouble() * b[k * 3 + i % 3] } }

    private const val MIN_GAIN = 0.25f
    private const val MAX_GAIN = 16f
}
