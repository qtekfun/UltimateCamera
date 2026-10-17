package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Test scenes generated in code. No real photos are ever committed to this repository: what the owner's shots taught
 * us is kept as the numbers these scenes are built to reproduce.
 */
internal object SyntheticScenes {
    const val WIDTH = 300
    const val HEIGHT = 400

    /** 0..1 scene value: a textured sky in the top quarter, a ground gradient with objects and deep shadows below. */
    private fun scene(x: Int, y: Int): Float {
        val u = x / (WIDTH - 1f)
        val v = y / (HEIGHT - 1f)
        val texture = 0.04f * sin(2 * PI * (x / 17.0 + y / 23.0)).toFloat() + 0.03f * sin(2 * PI * (x / 7.0 - y / 11.0)).toFloat()
        return if (v < 0.25f) {
            // Sky: bright, with cloud-like detail.
            (0.74f + 0.12f * sin(2 * PI * u * 3).toFloat() * (1 - v * 2) + texture).coerceIn(0f, 1f)
        } else if (u in 0.7f..0.85f && v in 0.4f..0.55f) {
            // A bright window: the scene's highlights.
            (0.95f + texture / 4).coerceIn(0f, 1f)
        } else {
            val shadow = if (u in 0.1f..0.35f && v > 0.5f) 0.25f else 1f
            val objects = if (((x / 40) + (y / 50)) % 3 == 0) 0.15f else 0f
            ((0.15f + 0.55f * (1 - v) + 0.2f * u + objects) * shadow + texture).coerceIn(0f, 1f)
        }
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        fun q(c: Float) = (c * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (q(r) shl 16) or (q(g) shl 8) or q(b)
    }

    /** A colour around [luma] with the given [chroma], hue varying across the frame (foliage, warm and cool areas). */
    private fun colour(luma: Float, chroma: Float, x: Int, y: Int): Int {
        val hue = 2 * PI * ((x + 2 * y) % WIDTH) / WIDTH
        val r = luma + chroma * sin(hue).toFloat()
        val g = luma + chroma * sin(hue + 2 * PI / 3).toFloat()
        val b = luma + chroma * sin(hue + 4 * PI / 3).toFloat()
        return pack(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** Like our v0.6.1 HDR Auto shot: lifted blacks (p1 ≈ 32), flat contrast (sd < 58), muted colour. */
    fun washedOut(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        val l = 0.11f + scene(x, y) * 0.68f
        colour(l, 0.02f, x, y)
    }

    /** Like our v0.6.2 fusion: about 1.2 stops dark, highlights short of white (p50 ≈ 78, p99 ≈ 205), sky on top. */
    fun darkFusion(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        val l = scene(x, y).pow(1.8f) * 0.88f
        colour(l, 0.03f, x, y)
    }
}
