package com.qtekfun.ultimatecamera.core.codec

/**
 * The file a take is written as (D69): MediaMuxer always writes MP4; MOV is the same file turned into a QuickTime
 * movie by the post-process (`ftyp qt  `, QuickTime handlers and colour box), named `.mov`.
 */
enum class VideoContainer(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    MOV("mov", "video/quicktime")
}
