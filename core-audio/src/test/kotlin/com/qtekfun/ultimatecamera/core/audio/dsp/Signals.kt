package com.qtekfun.ultimatecamera.core.audio.dsp

import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assume.assumeTrue

/** Synthetic test signals, interleaved, full scale ±1.0. */
object Signals {
    const val RATE = 48_000

    fun sine(frames: Int, channels: Int, hz: Double, amplitude: Double, rate: Int = RATE): FloatArray = FloatArray(frames * channels) { i ->
        (amplitude * sin(2 * PI * hz * (i / channels) / rate)).toFloat()
    }

    /** Exponential sweep from [fromHz] to [toHz] over [frames]. */
    fun sweep(frames: Int, channels: Int, fromHz: Double, toHz: Double, amplitude: Double, rate: Int = RATE): FloatArray {
        val duration = frames.toDouble() / rate
        val k = ln(toHz / fromHz)
        return FloatArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / rate
            (amplitude * sin(2 * PI * fromHz * duration / k * (exp(t / duration * k) - 1))).toFloat()
        }
    }

    fun maxAbs(buffer: FloatArray, from: Int = 0, to: Int = buffer.size): Float {
        var m = 0f
        for (i in from until to) m = maxOf(m, kotlin.math.abs(buffer[i]))
        return m
    }

    /** Bytes allocated by the current thread while running [block]; skips the test where the JVM cannot tell. */
    fun allocatedBytes(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("thread allocation counter unavailable", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }
}
