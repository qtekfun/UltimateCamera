package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Row access to a full-size ARGB_8888 photo (the layout of `Bitmap.getPixels`), so the blur runs on a bitmap on the
 * phone and on plain arrays in JVM tests. The CPU reference locks the instance around each call.
 */
interface ArgbBands {
    val width: Int
    val height: Int

    /** Copies rows [top] until `top + rows` into [into] (`width * rows` values). */
    fun read(top: Int, rows: Int, into: IntArray)

    /** Replaces rows [top] until `top + rows` with [from]. */
    fun write(top: Int, rows: Int, from: IntArray)
}

/** [ArgbBands] over one array of `width * height` pixels (tests, small images). */
class ArrayBands(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) : ArgbBands {
    init {
        require(pixels.size == width * height) { "pixels ${pixels.size} for ${width}x$height" }
    }

    override fun read(top: Int, rows: Int, into: IntArray) = System.arraycopy(pixels, top * width, into, 0, rows * width)

    override fun write(top: Int, rows: Int, from: IntArray) = System.arraycopy(from, 0, pixels, top * width, rows * width)
}

/**
 * One portrait blur (D73, D92): the reduced copy of the photo ([small], the same pixels as packed ARGB in [smallArgb],
 * and its [luma]), the depth upsampler [guide] whose guide is [luma], the focus and the f-number, the gather [kernel]
 * and radius bound. [cpu] is the reference: the reduced CoC per pixel, the gather, the CoC edge mask and the full-size
 * composite ([CompositePlan]); `GpuPortraitKernels.blur` runs the same maths in four passes without leaving the GPU.
 */
@Suppress("LongParameterList")
class BlurJob(
    val small: RgbTile,
    val smallArgb: IntArray,
    val luma: Plane,
    val guide: GuidedUpsample,
    val focus: Float,
    val fNumber: Float,
    val kernel: DiscKernel = DiscKernel.CAPTURE,
    val maxRadius: Float = DepthBlur.MAX_RADIUS
) {
    init {
        require(smallArgb.size == small.width * small.height && luma.width == small.width && luma.height == small.height) { "reduced sizes differ" }
    }

    /** The CoC diameter of every reduced pixel (reduced pixels): guided depth, [Bokeh.cocPx], rows on [pool]. */
    fun cocLow(pool: ExecutorService): Plane {
        val coc = Plane(small.width, small.height)
        inBands(coc.height, pool) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until coc.width) {
                    val inverse = guide.at((x + HALF) / coc.width, (y + HALF) / coc.height, luma[x, y])
                    coc[x, y] = Bokeh.cocPx(inverse, focus, fNumber, coc.width)
                }
            }
        }
        return coc
    }

    /**
     * The CPU reference over [image]: the reduced CoC, the gather by [gather] (the CPU's [DepthBlur.gather] by default),
     * the edge mask and the composite from row [fromRow] on (rows above it are taken as already done).
     */
    fun cpu(image: ArgbBands, fromRow: Int = 0, gather: (RgbTile, Plane) -> RgbTile = { tile, coc -> DepthBlur.gather(tile, coc, kernel, maxRadius) }) {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            val coc = cocLow(pool)
            val blurred = gather(small, coc)
            CompositePlan(image.width, image.height, blurred, coc, CompositePlan.edgeMask(coc), guide, focus, fNumber).cpu(image, fromRow, pool)
        } finally {
            pool.shutdown()
        }
    }

    private companion object {
        const val HALF = 0.5f
    }
}

/**
 * The full-size composite of a portrait (D73): the gather output [blurred] and the reduced CoC [cocLow] (same size),
 * the CoC [edges] of [cocLow] (where the edge-aware depth is used), the depth upsampler [guide], the focus and the
 * f-number, for a photo [width] x [height]. [cocAt] is the CoC of one full-size pixel: guided depth on the edges, the
 * bilinear reduced CoC elsewhere.
 */
@Suppress("LongParameterList")
class CompositePlan(
    val width: Int,
    val height: Int,
    val blurred: RgbTile,
    val cocLow: Plane,
    val edges: BooleanArray,
    val guide: GuidedUpsample,
    val focus: Float,
    val fNumber: Float
) {
    init {
        require(blurred.width == cocLow.width && blurred.height == cocLow.height) { "blurred and CoC sizes differ" }
        require(edges.size == cocLow.width * cocLow.height) { "edge mask size" }
    }

    /** Full-size pixels per reduced pixel. */
    val scale: Float = width.toFloat() / cocLow.width

    private val field = CocField { nx, ny, luma -> cocAt(nx, ny, luma) }

    /** The full-size CoC diameter at the normalised position ([nx], [ny]) of a pixel with [luma]. */
    fun cocAt(nx: Float, ny: Float, luma: Float): Float {
        val lx = (nx * cocLow.width).toInt().coerceIn(0, cocLow.width - 1)
        val ly = (ny * cocLow.height).toInt().coerceIn(0, cocLow.height - 1)
        // Edge-aware depth only where the blur changes (the subject's outline); bilinear elsewhere is enough.
        return if (edges[ly * cocLow.width + lx]) {
            Bokeh.cocPx(guide.at(nx, ny, luma), focus, fNumber, width)
        } else {
            DepthBlur.bilinear(cocLow, nx, ny) * scale
        }
    }

    /** One band of the composite in place ([DepthBlur.composite] with [cocAt]). */
    fun band(argb: IntArray, top: Int) = DepthBlur.composite(argb, top, width, height, blurred, field)

    /** The composite over [image] from row [fromRow] on, in row bands on [pool] (D90: pixels are independent). */
    fun cpu(image: ArgbBands, fromRow: Int, pool: ExecutorService) {
        require(image.width == width && image.height == height) { "image size" }
        if (fromRow >= height) return
        val jobs = (fromRow until height step BAND_ROWS).map { top ->
            Callable {
                val rows = minOf(BAND_ROWS, height - top)
                val pixels = IntArray(width * rows)
                synchronized(image) { image.read(top, rows, pixels) }
                band(pixels, top)
                synchronized(image) { image.write(top, rows, pixels) }
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
    }

    companion object {
        private const val BAND_ROWS = 128

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
    }
}

/** [block] over rows 0 until [n] in one band per core on [pool]. */
internal inline fun inBands(n: Int, pool: ExecutorService, crossinline block: (Int, Int) -> Unit) {
    val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, maxOf(1, n))
    pool.invokeAll((0 until bands).map { i -> Callable { block(n * i / bands, n * (i + 1) / bands) } }).forEach { it.get() }
}
