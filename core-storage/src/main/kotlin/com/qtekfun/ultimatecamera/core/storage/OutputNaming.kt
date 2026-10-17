package com.qtekfun.ultimatecamera.core.storage

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The files of one take, all sharing [base] so they sort together and editors match them.
 *
 * Example: `UC_20261016_143005_CAM0_T001.mp4`, `UC_20261016_143005_CAM0_T001.wav` (separate WAV, REQ-AUD-003),
 * `UC_20261016_143005_CAM0_T001_proxy.mp4` (later).
 */
data class ClipNames(val base: String, val take: Int) {
    val video: String get() = "$base.${OutputNaming.VIDEO_EXTENSION}"
    val wav: String get() = "$base.${OutputNaming.WAV_EXTENSION}"
    val proxy: String get() = "${base}${OutputNaming.PROXY_SUFFIX}.${OutputNaming.VIDEO_EXTENSION}"

    /** Every name this take may create; a collision with any of them moves to the next take. */
    val all: List<String> get() = listOf(video, wav, proxy)
}

/**
 * Deterministic clip names: `UC_<yyyyMMdd>_<HHmmss>_CAM<camera>_T<take>`. Only `A-Z a-z 0-9 _ - .` are used, so the
 * names are valid on FAT32/exFAT (USB SSDs, SD cards), ext4/f2fs and in every desktop OS. Local time, as on the phone's
 * clock, because that is what the operator sees; the take counter disambiguates within a second.
 */
object OutputNaming {
    const val PREFIX = "UC"
    const val VIDEO_EXTENSION = "mp4"
    const val WAV_EXTENSION = "wav"
    const val PROXY_SUFFIX = "_proxy"
    const val PHOTO_EXTENSION = "jpg"
    const val MAX_CAMERA_ID_LENGTH = 16
    const val TAKE_DIGITS = 3

    /** Upper bound of [next]'s search; reaching it means something is badly wrong with the folder listing. */
    const val MAX_ATTEMPTS = 10_000

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    fun names(time: LocalDateTime, cameraId: String, take: Int): ClipNames {
        require(take >= 1) { "take starts at 1" }
        val takeText = take.toString().padStart(TAKE_DIGITS, '0')
        return ClipNames("${PREFIX}_${DATE_TIME.format(time)}_CAM${sanitizeCameraId(cameraId)}_T$takeText", take)
    }

    /**
     * The first take from [firstTake] on whose names are all absent from [existingNames] (compared ignoring case:
     * FAT and exFAT are case-insensitive).
     */
    fun next(time: LocalDateTime, cameraId: String, firstTake: Int, existingNames: Set<String>): ClipNames {
        val existing = existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (take in firstTake until firstTake + MAX_ATTEMPTS) {
            val candidate = names(time, cameraId, take)
            if (candidate.all.none { it.lowercase(Locale.ROOT) in existing }) return candidate
        }
        error("no free clip name after $MAX_ATTEMPTS takes")
    }

    /** A photo's name: `UC_<yyyyMMdd>_<HHmmss>_CAM<camera>_P<shot>.jpg` (decision D41). */
    fun photoName(time: LocalDateTime, cameraId: String, shot: Int): String {
        require(shot >= 1) { "shot starts at 1" }
        val shotText = shot.toString().padStart(TAKE_DIGITS, '0')
        return "${PREFIX}_${DATE_TIME.format(time)}_CAM${sanitizeCameraId(cameraId)}_P$shotText.$PHOTO_EXTENSION"
    }

    /** The first photo name from shot 1 on that is absent from [existingNames] (ignoring case, as [next]). */
    fun nextPhoto(time: LocalDateTime, cameraId: String, existingNames: Set<String>): String {
        val existing = existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (shot in 1..MAX_ATTEMPTS) {
            val candidate = photoName(time, cameraId, shot)
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
        }
        error("no free photo name after $MAX_ATTEMPTS shots")
    }

    /** Camera2 ids are usually digits; anything else becomes `-`, and an empty id becomes `X`. */
    fun sanitizeCameraId(cameraId: String): String {
        val safe = cameraId.map { if (it.isAsciiLetterOrDigit()) it else '-' }.joinToString("").take(MAX_CAMERA_ID_LENGTH)
        return safe.ifEmpty { "X" }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
