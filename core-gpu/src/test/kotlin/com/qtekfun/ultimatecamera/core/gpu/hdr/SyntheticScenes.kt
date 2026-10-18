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

    /** Where [indoorJpegR] puts its parts, in pixels of the 300 × 400 frame. */
    object Indoor {
        /** The white wall: right of the door, top 60 %. */
        fun wall(x: Int, y: Int) = x >= 150 && y < 240

        /** The dark corridor seen through the door: left half, top 60 %. */
        fun corridor(x: Int, y: Int) = x < 150 && y < 240

        /** A black object (a chair) at the bottom left. */
        fun black(x: Int, y: Int) = x < 60 && y >= 240

        fun skin(x: Int, y: Int) = x in 180 until 230 && y in 290 until 340

        fun sky(x: Int, y: Int) = x in 240 until 290 && y in 290 until 340
    }

    /**
     * Like the camera's own JPEG_R SDR base on the owner's OPPO (v0.7.1, indoor room): a neutral white wall at only
     * about 205 (the base leaves room for its gain map), a warm dark corridor through a door at about 50, a chair whose
     * black sits at about 23 (lifted), a desk with a grey ramp, and skin and sky patches. Fine texture everywhere.
     */
    fun indoorJpegR(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        val t = 0.012f * sin(2 * PI * (x / 9.0 + y / 13.0)).toFloat() + 0.008f * sin(2 * PI * (x / 5.0 - y / 7.0)).toFloat()
        when {
            Indoor.wall(x, y) -> pack(0.80f + t, 0.805f + t, 0.815f + t)
            Indoor.corridor(x, y) -> pack(0.26f + 2 * t, 0.19f + 2 * t, 0.14f + 2 * t)
            Indoor.black(x, y) -> pack(0.09f + t / 2, 0.09f + t / 2, 0.095f + t / 2)
            Indoor.skin(x, y) -> pack(0.82f + t, 0.62f + t, 0.52f + t)
            Indoor.sky(x, y) -> pack(0.52f + t, 0.66f + t, 0.86f + t)
            else -> {
                // Desk: a ramp from shadow to near the wall's level, with a mildly colourful print.
                val v = 0.12f + 0.66f * (x - 60) / (WIDTH - 61f)
                colour(v + t, 0.06f, x, y)
            }
        }
    }

    /** A hard vertical edge: bright wall (display [bright]) left of x = [edge], dark area ([dark]) right of it. */
    fun edge(bright: Float = 0.8f, dark: Float = 0.15f, edge: Int = WIDTH / 2): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val v = if (i % WIDTH < edge) bright else dark
        pack(v, v, v)
    }

    /** A grey ramp from black to white, left to right. */
    fun ramp(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val v = (i % WIDTH) / (WIDTH - 1f)
        pack(v, v, v)
    }

    /** Like our v0.6.2 fusion: about 1.2 stops dark, highlights short of white (p50 ≈ 78, p99 ≈ 205), sky on top. */
    fun darkFusion(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        val l = scene(x, y).pow(1.8f) * 0.88f
        colour(l, 0.03f, x, y)
    }
}
