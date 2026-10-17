package com.qtekfun.ultimatecamera.core.recording

import android.media.ExifInterface
import android.os.Build
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** EXIF for the JPEGs we encode ourselves (D64); the camera's own JPEGs already carry the HAL's EXIF. */
internal object PhotoExif {
    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Date, maker and model, and orientation "normal" (the pixels are already upright). Best effort. */
    fun stamp(jpeg: File, taken: LocalDateTime) {
        runCatching {
            val exif = ExifInterface(jpeg.path)
            val time = EXIF_TIME.format(taken)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, time)
            exif.setAttribute(ExifInterface.TAG_DATETIME, time)
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
        }
    }
}
