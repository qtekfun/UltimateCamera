package com.qtekfun.ultimatecamera.core.net.monitor

import kotlin.math.roundToInt
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Where the stamp row sits: the vertical centre of a strip covering the top 4 % of the frame. */
internal object StampStrip {
    private const val ROW_FRACTION = 0.02

    /** The row the decoder reads, y = round(0.02 * height). */
    fun row(height: Int): Int = (ROW_FRACTION * height).roundToInt().coerceIn(0, height - 1)

    /**
     * Top of the two-row band that is converted: even, so I420 chroma stays aligned, and it contains [row]. The band
     * is two rows tall because a one-row I420 buffer has no well-defined chroma row.
     */
    fun bandTop(height: Int): Int = (row(height) and 1.inv()).coerceIn(0, maxOf(0, height - BAND_ROWS))

    const val BAND_ROWS = 2
}

/**
 * Reads the stamp of every decoded frame and hands the counter to [onCounter]. Runs on libwebrtc's decoder output
 * thread. Cheap by construction: the frame is cropped to a two-row band first (for a texture frame that only changes
 * the transform matrix), so toI420() converts width x 2 pixels instead of the whole frame. Every buffer it creates is
 * released before returning; the frame itself is not retained.
 *
 * Per frame it allocates only what libwebrtc's crop and conversion allocate (two small buffer objects and width x 3
 * bytes); the luma row array is reused.
 */
internal class StampSink(private val decoder: StampDecoder?, private val onCounter: (Int) -> Unit) : VideoSink {
    @Volatile var framesReceived = 0
        private set

    @Volatile var stampsDecoded = 0
        private set

    @Volatile var readFailures = 0
        private set

    private var luma = IntArray(0)

    // Decoder-thread boundary: a failed conversion of one frame is counted, never thrown back into libwebrtc.
    @Suppress("TooGenericExceptionCaught")
    override fun onFrame(frame: VideoFrame) {
        framesReceived++
        val stamp = decoder ?: return
        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height < StampStrip.BAND_ROWS) {
            readFailures++
            return
        }
        val top = StampStrip.bandTop(height)
        var band: VideoFrame.Buffer? = null
        var i420: VideoFrame.I420Buffer? = null
        try {
            band = buffer.cropAndScale(0, top, width, StampStrip.BAND_ROWS, width, StampStrip.BAND_ROWS)
            i420 = band.toI420()
            if (i420 == null) {
                readFailures++
                return
            }
            val y = i420.dataY
            val start = y.position() + (StampStrip.row(height) - top) * i420.strideY
            if (luma.size < width) luma = IntArray(width)
            for (x in 0 until width) luma[x] = y.get(start + x).toInt() and 0xFF
            val counter = stamp.decode(luma, width) ?: return
            stampsDecoded++
            onCounter(counter)
        } catch (_: RuntimeException) {
            readFailures++
        } finally {
            i420?.release()
            band?.release()
        }
    }
}
