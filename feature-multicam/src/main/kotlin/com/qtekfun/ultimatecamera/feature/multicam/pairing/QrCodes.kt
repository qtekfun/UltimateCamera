package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR encode/decode with ZXing core (pure Java). Pure: no Android types, so it is unit tested on the JVM. */
object QrCodes {
    /** The modules of a QR code for [text] (medium error correction, 2-module quiet zone); `true` is dark. */
    fun encode(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    )

    /**
     * Decodes a QR code from an 8-bit luminance plane (the Y plane of a YUV_420_888 camera frame: [rowStride] may be
     * wider than [width]). Null when no readable code is in the frame.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            QRCodeReader().decode(bitmap, hints).text
        } catch (_: ReaderException) {
            // Not found, checksum or format error: just no code in this frame.
            null
        }
    }

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )
}
