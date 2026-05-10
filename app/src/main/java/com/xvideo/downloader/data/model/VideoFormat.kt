package com.xvideo.downloader.data.model

data class VideoFormat(
    val quality: String,
    val url: String,
    val format: String,
    val size: Long = 0
) {
    fun getSizeLabel(): String {
        return if (size > 0) {
            when {
                size < 1024 -> "${size}B"
                size < 1024 * 1024 -> "${size / 1024}KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)}MB"
                else -> "${size / (1024 * 1024 * 1024)}GB"
            }
        } else {
            ""
        }
    }
}
