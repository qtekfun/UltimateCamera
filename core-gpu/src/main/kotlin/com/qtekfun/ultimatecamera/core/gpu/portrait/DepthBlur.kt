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

    /** Disc samples of [gather] (a golden-angle spiral, even coverage at any radius). */
    private const val SAMPLES = 96

    private val GOLDEN_ANGLE = PI * (3.0 - sqrt(5.0))
    private val spiral: FloatArray = FloatArray(SAMPLES * 2).also { s ->
        for (i in 0 until SAMPLES) {
            val r = sqrt((i + 0.5) / SAMPLES)
            val a = i * GOLDEN_ANGLE
            s[i * 2] = (r * cos(a)).toFloat()
            s[i * 2 + 1] = (r * sin(a)).toFloat()
        }
    }

    /** Distance of each spiral sample from the centre (unit radius): no square root per sample. */
    private val spiralDistance: FloatArray = FloatArray(SAMPLES) { i -> sqrt((i + 0.5f) / SAMPLES) }

    /**
     * Blurs [image] with the per-pixel circle-of-confusion diameters [coc] (reduced pixels, same size as [image]);
     * radii are capped at [MAX_RADIUS].
     */
    fun gather(image: RgbTile, coc: Plane): RgbTile {
        val w = image.width
        val h = image.height
        val out = RgbTile(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) gatherPixel(image, coc, x, y, out)
        }
        return out
    }

    /** One pixel of [gather]: the disc average where each sample counts only if its own blur reaches the pixel. */
    private fun gatherPixel(image: RgbTile, coc: Plane, x: Int, y: Int, out: RgbTile) {
        val w = image.width
        val i = y * w + x
        val radius = (coc.data[i] / 2f).coerceAtMost(MAX_RADIUS)
        var r = image.r.data[i]
        var g = image.g.data[i]
        var b = image.b.data[i]
        var weights = 1f
        if (radius >= HALF_PIXEL) {
            for (s in 0 until SAMPLES) {
                val ox = spiral[s * 2] * radius
                val oy = spiral[s * 2 + 1] * radius
                val sx = floor(x + ox + HALF_PIXEL).toInt().coerceIn(0, w - 1)
                val sy = floor(y + oy + HALF_PIXEL).toInt().coerceIn(0, image.height - 1)
                val j = sy * w + sx
                val reach = (coc.data[j] / 2f).coerceAtMost(MAX_RADIUS)
                val weight = (reach - spiralDistance[s] * radius + 1f).coerceIn(0f, 1f)
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
