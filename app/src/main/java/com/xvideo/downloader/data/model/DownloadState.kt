package com.xvideo.downloader.data.model

sealed class DownloadState {
    object Idle : DownloadState()
    data class Parsing(val url: String) : DownloadState()
    data class Ready(val videoInfo: VideoInfo) : DownloadState()
    data class Downloading(
        val taskId: String,
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class Paused(val taskId: String, val progress: Int) : DownloadState()
    data class Completed(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

data class DownloadTask(
    val id: String,
    val videoInfo: VideoInfo,
    val variant: VideoVariant,
    val outputPath: String,
    val url: String,
    var progress: Int = 0,
    var downloadedBytes: Long = 0,
    var totalBytes: Long = 0,
    var state: DownloadTaskState = DownloadTaskState.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null
)

enum class DownloadTaskState {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
