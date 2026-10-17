package com.qtekfun.ultimatecamera.core.gpu.meters

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** Rec. 709 luma weights, used by every meter. */
object Luma {
    const val KR = 0.2126f
    const val KG = 0.7152f
    const val KB = 0.0722f

    fun of(r: Float, g: Float, b: Float): Float = r * KR + g * KG + b * KB
}

/**
 * CPU reference (the specification) of what `MonitorOverlayRenderer` draws on the preview: the source fitted and
 * desqueezed into the viewport, false colour, zebras and peaking computed per output pixel, then the guides blended on
 * top. Images are ARGB_8888 arrays in GL order (row 0 is the bottom row, as uploaded to and read back from a texture).
 * Not optimised: tests and documentation only.
 */
object MonitorOverlayMath {
    /** Distance of a decision value to its threshold below which the GPU may legitimately decide the other way. */
    const val AMBIGUOUS = 1e-4f

    /**
     * Renders [source] ([sourceWidth]×[sourceHeight]) as the overlay pass does into a [viewWidth]×[viewHeight]
     * viewport. When [margins] is given it receives, per output pixel, the smallest distance of any threshold
     * decision (peaking, zebra, false-colour edge) to its threshold; pixels below [AMBIGUOUS] can differ on the GPU.
     */
    fun render(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        settings: MonitorOverlaySettings,
        frameIndex: Long = 0,
        margins: FloatArray? = null
    ): IntArray {
        val out = IntArray(viewWidth * viewHeight) { 0xFF000000.toInt() }
        margins?.fill(Float.MAX_VALUE)
        val content = MonitorGeometry.contentRect(viewWidth, viewHeight, sourceWidth, sourceHeight, settings.desqueeze)
        val src = Sampler(source, sourceWidth, sourceHeight)
        val phase = stripePhase(settings.zebras, frameIndex)
        val rgb = FloatArray(3)
        for (py in 0 until content.height) {
            for (px in 0 until content.width) {
                val u = (px + 0.5f) / content.width
                val v = (py + 0.5f) / content.height
                val margin = shade(src, u, v, px, py, content.height, settings, phase, rgb)
                val index = (content.y + py) * viewWidth + content.x + px
                out[index] = Rgb8.argb(rgb)
                if (margins != null) margins[index] = margin
            }
        }
        val guides = GuideRects()
        GuideLayout.layout(settings.guides, content, guides)
        blendRects(out, viewWidth, viewHeight, guides)
        return out
    }

    /** Stripe offset in pixels for [frameIndex]: 0 unless the zebras are animated. */
    fun stripePhase(zebras: ZebraSettings, frameIndex: Long): Int =
        if (zebras.animated) ((frameIndex * zebras.speedPxPerFrame) % (2L * zebras.stripeWidthPx)).toInt() else 0

    /** Source-over blends [rects] into [image] like `glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)` on an RGBA8 target. */
    fun blendRects(image: IntArray, width: Int, height: Int, rects: GuideRects) {
        for (i in 0 until rects.count) {
            val color = rects.colors[i]
            val a = ((color ushr 24) and 0xFF) / 255f
            val x0 = rects.rects[i * 4].coerceIn(0, width)
            val y0 = rects.rects[i * 4 + 1].coerceIn(0, height)
            val x1 = (rects.rects[i * 4] + rects.rects[i * 4 + 2]).coerceIn(0, width)
            val y1 = (rects.rects[i * 4 + 1] + rects.rects[i * 4 + 3]).coerceIn(0, height)
            for (y in y0 until y1) {
                for (x in x0 until x1) image[y * width + x] = Rgb8.blend(image[y * width + x], color, a)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun shade(src: Sampler, u: Float, v: Float, px: Int, py: Int, contentHeight: Int, s: MonitorOverlaySettings, phase: Int, rgb: FloatArray): Float {
        src.sample(u, v, rgb)
        val y = Luma.of(rgb[0], rgb[1], rgb[2])
        var margin = Float.MAX_VALUE
        if (s.falseColor.enabled) margin = min(margin, falseColor(s.falseColor, y, rgb))
        val zebras = s.zebras
        if (zebras.primary.enabled) {
            margin = min(margin, bandMargin(y, zebras.primary))
            if (inBand(y, zebras.primary) && (((px + py + phase) / zebras.stripeWidthPx) and 1) == 0) Rgb8.setColor(rgb, zebras.primary.color)
        }
        if (zebras.secondary.enabled) {
            margin = min(margin, bandMargin(y, zebras.secondary))
            val diagonal = px + (contentHeight - 1 - py) + phase
            if (inBand(y, zebras.secondary) && ((diagonal / zebras.stripeWidthPx) and 1) == 0) Rgb8.setColor(rgb, zebras.secondary.color)
        }
        if (s.peaking.enabled) {
            val e = abs(src.luma(u + src.stepX, v) - src.luma(u - src.stepX, v)) + abs(src.luma(u, v + src.stepY) - src.luma(u, v - src.stepY))
            margin = min(margin, abs(e - s.peaking.threshold))
            if (e > s.peaking.threshold) Rgb8.setColor(rgb, s.peaking.color)
        }
        return margin
    }

    private fun falseColor(settings: FalseColorSettings, y: Float, rgb: FloatArray): Float {
        var margin = Float.MAX_VALUE
        var hit = false
        for (band in settings.bands) {
            val lo = band.minPercent / 100f
            val hi = band.maxPercent / 100f
            if (!hit) margin = min(margin, min(abs(y - lo), abs(y - hi)))
            if (!hit && y >= lo && y < hi) {
                Rgb8.setColor(rgb, band.color)
                hit = true
            }
        }
        if (!hit) rgb.fill(y)
        return margin
    }

    private fun inBand(y: Float, band: ZebraBand) = y >= band.minPercent / 100f && y <= band.maxPercent / 100f

    private fun bandMargin(y: Float, band: ZebraBand) = min(abs(y - band.minPercent / 100f), abs(y - band.maxPercent / 100f))

    /** Bilinear, clamp-to-edge sampling of an ARGB image like a linearly filtered RGBA8 texture (8-bit weights). */
    internal class Sampler(private val pixels: IntArray, private val width: Int, private val height: Int) {
        val stepX = 1f / width
        val stepY = 1f / height
        private val tmp = FloatArray(3)

        fun luma(u: Float, v: Float): Float {
            sample(u, v, tmp)
            return Luma.of(tmp[0], tmp[1], tmp[2])
        }

        fun sample(u: Float, v: Float, out: FloatArray) {
            val tx = u * width - 0.5f
            val ty = v * height - 0.5f
            val x0 = floor(tx).toInt()
            val y0 = floor(ty).toInt()
            val fx = quantise(tx - x0)
            val fy = quantise(ty - y0)
            for (c in 0..2) {
                val shift = 16 - c * 8
                val top = lerp(texel(x0, y0 + 1, shift), texel(x0 + 1, y0 + 1, shift), fx)
                val bottom = lerp(texel(x0, y0, shift), texel(x0 + 1, y0, shift), fx)
                out[c] = lerp(bottom, top, fy)
            }
        }

        private fun texel(x: Int, y: Int, shift: Int): Float =
            ((pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)] shr shift) and 0xFF) / 255f

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        /** Filter weights snap to 1/256 like the texture units of mobile GPUs, so exact texel centres stay exact. */
        private fun quantise(f: Float): Float = (f * 256f + 0.5f).toInt() / 256f
    }
}

/** 8-bit colour helpers shared by the CPU references. */
internal object Rgb8 {
    fun setColor(rgb: FloatArray, argb: Int) {
        rgb[0] = ((argb shr 16) and 0xFF) / 255f
        rgb[1] = ((argb shr 8) and 0xFF) / 255f
        rgb[2] = (argb and 0xFF) / 255f
    }

    fun argb(rgb: FloatArray): Int = (0xFF shl 24) or (to8(rgb[0]) shl 16) or (to8(rgb[1]) shl 8) or to8(rgb[2])

    fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    fun blend(dst: Int, src: Int, a: Float): Int {
        var result = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val d = ((dst shr shift) and 0xFF) / 255f
            val s = ((src shr shift) and 0xFF) / 255f
            result = result or (to8(s * a + d * (1f - a)) shl shift)
        }
        return result
    }

    fun addColor(rgb: FloatArray, color: Int, intensity: Float) {
        rgb[0] = min(1f, rgb[0] + ((color shr 16) and 0xFF) / 255f * intensity)
        rgb[1] = min(1f, rgb[1] + ((color shr 8) and 0xFF) / 255f * intensity)
        rgb[2] = min(1f, rgb[2] + (color and 0xFF) / 255f * intensity)
    }
}
