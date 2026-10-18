package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile

/**
 * The two heavy steps of the portrait render (D73, D87): the depth-aware gather blur and the stereo block matching.
 * [CpuPortraitKernels] is the reference (JVM-tested); `GpuPortraitKernels` runs the same maths in GLES 3.0 shaders.
 * Use one instance on one thread and [close] it there.
 */
interface PortraitKernels : AutoCloseable {
    /** "gpu" or "cpu", for the render log. */
    val name: String

    /** [DepthBlur.gather] with [kernel] and radii capped at [maxRadius]. */
    fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel = DiscKernel.CAPTURE, maxRadius: Float = DepthBlur.MAX_RADIUS): RgbTile

    /** [StereoDepth.disparity]. */
    fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int = DEFAULT_BLOCK_RADIUS): Plane

    /**
     * The whole portrait blur of [job] into the full-size [image] in place, rows [fromRow] on (D92). The default is the
     * CPU reference ([BlurJob.cpu]) with this instance's [gather]; `GpuPortraitKernels` keeps every intermediate on the
     * GPU and writes whole row bands in order, each only after GL reported no error.
     */
    fun blur(job: BlurJob, image: ArgbBands, fromRow: Int = 0) = job.cpu(image, fromRow) { tile, coc -> gather(tile, coc, job.kernel, job.maxRadius) }

    override fun close() = Unit

    companion object {
        const val DEFAULT_BLOCK_RADIUS = 3
    }
}

/** The CPU reference: [DepthBlur] and [StereoDepth] as they are. */
object CpuPortraitKernels : PortraitKernels {
    override val name: String = "cpu"

    override fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel, maxRadius: Float): RgbTile = DepthBlur.gather(image, coc, kernel, maxRadius)

    override fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int): Plane =
        StereoDepth.disparity(main, second, maxDisparity, blockRadius)
}

/**
 * Whether the GPU kernels still deserve a try in this process: the first failure (no context, a shader the driver
 * rejects, a texture too large) is remembered, so later shots go straight to the CPU and never pay for a second
 * failure. Thread-safe.
 */
class KernelHealth {
    @Volatile var failure: String? = null
        private set

    val gpuUsable: Boolean get() = failure == null

    fun fail(reason: String) {
        if (failure == null) failure = reason
    }
}

/**
 * The GPU kernels with the CPU as fallback (D87): [open] makes the GPU kernels on first use (on the calling thread,
 * which then owns them); if that or any GPU call throws, the call is redone on the CPU, [health] remembers the
 * failure and this instance stays on the CPU. The photo is never lost to the GPU.
 */
class FallbackPortraitKernels(private val health: KernelHealth, private val open: () -> PortraitKernels, private val onFallback: (String) -> Unit = {}) :
    PortraitKernels {
    private var primary: PortraitKernels? = null
    private var onCpu = !health.gpuUsable

    override val name: String get() = if (onCpu) CpuPortraitKernels.name else primary?.name ?: "gpu"

    override fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel, maxRadius: Float): RgbTile =
        attempt("gather", { it.gather(image, coc, kernel, maxRadius) }) { CpuPortraitKernels.gather(image, coc, kernel, maxRadius) }

    override fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int): Plane = attempt("disparity", {
        it.disparity(main, second, maxDisparity, blockRadius)
    }) { CpuPortraitKernels.disparity(main, second, maxDisparity, blockRadius) }

    /** On the GPU; a failure part-way is finished on the CPU from the first row the GPU did not write. */
    override fun blur(job: BlurJob, image: ArgbBands, fromRow: Int) {
        val written = WrittenRows(image, fromRow)
        attempt("blur", { it.blur(job, written, fromRow) }) { CpuPortraitKernels.blur(job, image, written.done) }
    }

    /** Counts the rows written in order from [fromRow], so a GPU blur that stops part-way is finished from [done]. */
    private class WrittenRows(private val image: ArgbBands, fromRow: Int) : ArgbBands by image {
        @Volatile var done: Int = fromRow
            private set

        override fun write(top: Int, rows: Int, from: IntArray) {
            image.write(top, rows, from)
            if (top == done) done = top + rows
        }
    }

    @Suppress("TooGenericExceptionCaught") // GL drivers throw anything; the CPU reference then does the work.
    private inline fun <T> attempt(step: String, gpu: (PortraitKernels) -> T, cpu: () -> T): T {
        if (onCpu) return cpu()
        return try {
            gpu(primary ?: open().also { primary = it })
        } catch (e: Exception) {
            val reason = "$step: ${e.javaClass.simpleName}: ${e.message}"
            health.fail(reason)
            onCpu = true
            closePrimary()
            onFallback(reason)
            cpu()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Releasing a broken context must not hide the fallback.
    private fun closePrimary() {
        try {
            primary?.close()
        } catch (_: Exception) {
            // Nothing more to free.
        }
        primary = null
    }

    override fun close() = closePrimary()
}
