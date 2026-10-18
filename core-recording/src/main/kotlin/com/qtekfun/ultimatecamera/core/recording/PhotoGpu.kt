package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.util.Log
import com.qtekfun.ultimatecamera.core.gpu.portrait.KernelHealth

/**
 * The GPU passes of the photo pipeline (D90: base rendering, style) with the CPU as fallback: [attempt] runs the GPU
 * step and, if it throws (no context, a shader the driver rejects, a texture too large), logs why, remembers the
 * failure for the rest of the process in [health] and runs the CPU step instead. The photo is never lost to the GPU.
 */
object PhotoGpu {
    private const val TAG = "UcPhoto"

    val health = KernelHealth()

    /** Images below this many pixels go straight to the CPU: a context costs more than the pass saves. */
    const val MIN_PIXELS = 500_000

    /** Whether a [width] x [height] image should try the GPU first. */
    fun worth(width: Int, height: Int): Boolean = health.gpuUsable && width.toLong() * height >= MIN_PIXELS

    /** [gpu] when [tryGpu], else or after a failure [cpu]; returns which ran ("gpu" or "cpu"). */
    @Suppress("TooGenericExceptionCaught") // GL drivers throw anything; the CPU reference then does the work.
    inline fun attempt(step: String, tryGpu: Boolean, gpu: () -> Unit, cpu: () -> Unit): String {
        if (tryGpu) {
            try {
                gpu()
                return "gpu"
            } catch (e: Exception) {
                fallback(step, e)
            }
        }
        cpu()
        return "cpu"
    }

    /** [make] (a GPU pass), or null after logging and remembering why it failed. */
    @Suppress("TooGenericExceptionCaught")
    inline fun <T : Any> open(step: String, make: () -> T): T? = orNull(step, make)

    /** [block], or null after logging and remembering why it failed (the caller then does the step on the CPU). */
    @Suppress("TooGenericExceptionCaught")
    inline fun <T : Any> orNull(step: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        fallback(step, e)
        null
    }

    /** Closes a GPU pass; a failure there only costs a log line. */
    @Suppress("TooGenericExceptionCaught")
    fun close(pass: AutoCloseable) {
        try {
            pass.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing a GPU pass: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun fallback(step: String, e: Exception) {
        val reason = "$step: ${e.javaClass.simpleName}: ${e.message}"
        health.fail(reason)
        Log.w(TAG, "GPU $reason, using the CPU")
    }
}

/** Strip readers and writers of a bitmap for the GPU passes: `rows` ARGB rows from row `y`. */
fun Bitmap.reader(): (IntArray, Int, Int) -> Unit = { px, y, rows -> getPixels(px, 0, width, 0, y, width, rows) }

fun Bitmap.writer(): (IntArray, Int, Int) -> Unit = { px, y, rows -> setPixels(px, 0, width, 0, y, width, rows) }
