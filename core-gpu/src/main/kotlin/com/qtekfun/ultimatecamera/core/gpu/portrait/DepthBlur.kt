package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The depth-aware lens blur of a portrait photo (D73), in two steps so its cost stays bounded on modest phones:
 *
 * 1. [gather] blurs a reduced copy of the photo: every pixel averages the samples of a disc as wide as its circle of
 *    confusion, but a sample only counts when its own blur disc reaches the pixel (scatter-as-gather). A sharp subject
 *    therefore never smears into the blurred background around it.
 * 2. [composite] mixes each full-size pixel with the bilinear blurred colour by how large its own circle of confusion
 *    is at full size: in-focus pixels stay the camera's pixels.
 */
object DepthBlur {
    /** Largest blur radius in reduced pixels (the cost bound of [gather]). */
    const val MAX_RADIUS = 24f

    /**
     * Blurs [image] with the per-pixel circle-of-confusion diameters [coc] (reduced pixels, same size as [image]);
     * radii are capped at [maxRadius]. This is the CPU reference of the GPU gather (`PortraitShaders.gather`): the
     * same disc [kernel], the same sample rounding and the same weights.
     */
    fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel = DiscKernel.CAPTURE, maxRadius: Float = MAX_RADIUS): RgbTile {
        require(image.width == coc.width && image.height == coc.height) { "image and CoC sizes differ" }
        val w = image.width
        val h = image.height
        val out = RgbTile(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) gatherPixel(image, coc, x, y, out, kernel, maxRadius)
        }
        return out
    }

    /** One pixel of [gather]: the disc average where each sample counts only if its own blur reaches the pixel. */
    @Suppress("LongParameterList")
    private fun gatherPixel(image: RgbTile, coc: Plane, x: Int, y: Int, out: RgbTile, kernel: DiscKernel, maxRadius: Float) {
        val w = image.width
        val i = y * w + x
        val radius = (coc.data[i] / 2f).coerceAtMost(maxRadius)
        var r = image.r.data[i]
        var g = image.g.data[i]
        var b = image.b.data[i]
        var weights = 1f
        if (radius >= HALF_PIXEL) {
            for (s in 0 until kernel.samples) {
                val ox = kernel.x[s] * radius
                val oy = kernel.y[s] * radius
                val sx = floor(x + ox + HALF_PIXEL).toInt().coerceIn(0, w - 1)
                val sy = floor(y + oy + HALF_PIXEL).toInt().coerceIn(0, image.height - 1)
                val j = sy * w + sx
                val reach = (coc.data[j] / 2f).coerceAtMost(maxRadius)
                val weight = (reach - kernel.distance[s] * radius + 1f).coerceIn(0f, 1f)
                r += image.r.data[j] * weight
                g += image.g.data[j] * weight
                b += image.b.data[j] * weight
                weights += weight
            }
        }
        out.r.data[i] = r / weights
        out.g.data[i] = g / weights
        out.b.data[i] = b / weights
    }

    /**
     * How much of the blurred colour a full-size pixel takes for its circle-of-confusion diameter [cocFull] in
     * full-size pixels, when the blurred copy is [scale] times smaller: none under one pixel, all from two reduced
     * pixels on (smoothstep between).
     */
    fun blend(cocFull: Float, scale: Float): Float {
        val lo = 1f
        val hi = (2f * scale).coerceAtLeast(lo + 1f)
        val t = ((cocFull - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Mixes one row band of the full-size photo in place: [argb] holds rows [top] until `top + argb.size / width` of
     * an image [width] x [height]; [blurred] is the [gather] output; [coc] gives the full-size CoC diameter of a
     * pixel from its normalised position and its luma (the guide of the depth upsampling).
     */
    @Suppress("LongParameterList")
    fun composite(argb: IntArray, top: Int, width: Int, height: Int, blurred: RgbTile, coc: CocField) {
        val scale = width.toFloat() / blurred.width
        val rows = argb.size / width
        for (row in 0 until rows) {
            val y = top + row
            val ny = (y + HALF_PIXEL) / height
            for (x in 0 until width) {
                val i = row * width + x
                val c = argb[i]
                val nx = (x + HALF_PIXEL) / width
                val alpha = blend(coc.cocAt(nx, ny, Argb.luma(c)), scale)
                if (alpha <= 0f) continue
                val br = bilinear(blurred.r, nx, ny)
                val bg = bilinear(blurred.g, nx, ny)
                val bb = bilinear(blurred.b, nx, ny)
                argb[i] = Argb.mix(c, br, bg, bb, alpha)
            }
        }
    }

    /** Bilinear sample of [plane] at the normalised position ([nx], [ny]), pixel centres, clamped at the border. */
    fun bilinear(plane: Plane, nx: Float, ny: Float): Float {
        val fx = (nx * plane.width - HALF_PIXEL).coerceIn(0f, (plane.width - 1).toFloat())
        val fy = (ny * plane.height - HALF_PIXEL).coerceIn(0f, (plane.height - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = (x0 + 1).coerceAtMost(plane.width - 1)
        val y1 = (y0 + 1).coerceAtMost(plane.height - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = plane[x0, y0] + (plane[x1, y0] - plane[x0, y0]) * tx
        val bottom = plane[x0, y1] + (plane[x1, y1] - plane[x0, y1]) * tx
        return top + (bottom - top) * ty
    }

    private const val HALF_PIXEL = 0.5f
}

/**
 * The disc of the gather blur: [samples] points of a golden-angle spiral in the unit disc (even coverage at any radius),
 * with their distance from the centre precomputed (no square root per sample). The GPU gather gets exactly these
 * floats as uniforms, so CPU and GPU round the same sample positions.
 */
class DiscKernel(val samples: Int) {
    init {
        require(samples in 1..MAX_SAMPLES) { "disc samples $samples" }
    }

    val x = FloatArray(samples)
    val y = FloatArray(samples)
    val distance = FloatArray(samples)

    init {
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in 0 until samples) {
            val r = sqrt((i + 0.5) / samples)
            val a = i * golden
            x[i] = (r * cos(a)).toFloat()
            y[i] = (r * sin(a)).toFloat()
            distance[i] = sqrt((i + 0.5f) / samples)
        }
    }

    /** (x, y, distance) per sample, for `glUniform3fv`. */
    fun packed(): FloatArray = FloatArray(samples * 3) { i ->
        when (i % 3) {
            0 -> x[i / 3]
            1 -> y[i / 3]
            else -> distance[i / 3]
        }
    }

    companion object {
        /** Largest kernel the GPU shader declares (uniform vectors stay well under GLES 3.0's guaranteed 224). */
        const val MAX_SAMPLES = 96

        /** The capture blur: 96 samples. */
        val CAPTURE = DiscKernel(MAX_SAMPLES)

        /** The live preview: 32 samples on a small image (cost bound on modest GPUs). */
        val LIVE = DiscKernel(32)
    }
}

/**
 * The full-size CoC diameter of a pixel from its normalised position and luma. A `fun interface` with primitive
 * parameters, so the 12-megapixel composite calls it without boxing (a Kotlin function type boxed every argument).
 */
fun interface CocField {
    fun cocAt(nx: Float, ny: Float, luma: Float): Float
}

/** Packed ARGB_8888 helpers (the layout of `Bitmap.getPixels`). */
object Argb {
    private const val MAX = 255f

    fun r(c: Int): Float = (c shr 16 and 0xFF) / MAX

    fun g(c: Int): Float = (c shr 8 and 0xFF) / MAX

    fun b(c: Int): Float = (c and 0xFF) / MAX

    /** Rec.709 luma of the gamma-encoded values (the guide of the depth upsampling). */
    fun luma(c: Int): Float = 0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)

    fun pack(r: Float, g: Float, b: Float): Int = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    /** [c] mixed towards ([r], [g], [b]) by [alpha]. */
    fun mix(c: Int, r: Float, g: Float, b: Float, alpha: Float): Int = pack(r(c) + (r - r(c)) * alpha, g(c) + (g - g(c)) * alpha, b(c) + (b - b(c)) * alpha)

    private fun channel(v: Float): Int = (v * MAX + 0.5f).toInt().coerceIn(0, 255)
}
