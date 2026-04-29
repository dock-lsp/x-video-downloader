package com.xvideo.downloader.data.local

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.xvideo.downloader.data.model.DownloadTask
import com.xvideo.downloader.data.model.DownloadTaskState
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.local.database.AppDatabase
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

class DownloadManager(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _activeDownloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadTask>> = _activeDownloads.asStateFlow()

    private val _downloadProgress = MutableSharedFlow<DownloadProgress>()
    val downloadProgress: SharedFlow<DownloadProgress> = _downloadProgress.asSharedFlow()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    private val downloadDao by lazy {
        database.downloadHistoryDao()
    }

    fun getDownloadDirectory(): File {
        val dir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "XVideoDownloader")
        } else {
            File(context.filesDir, "downloads")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun startDownload(
        videoInfo: VideoInfo,
        variant: VideoVariant,
        taskId: String = UUID.randomUUID().toString()
    ): String {
        val fileName = "video_${videoInfo.tweetId}_${variant.getQualityLabel()}.mp4"
        val outputFile = File(getDownloadDirectory(), fileName)

        val task = DownloadTask(
            id = taskId,
            videoInfo = videoInfo,
            variant = variant,
            outputPath = outputFile.absolutePath,
            url = variant.url,
            state = DownloadTaskState.PENDING
        )

        // Save to database
        val entity = DownloadHistoryEntity(
            id = taskId,
            tweetId = videoInfo.tweetId,
            tweetUrl = videoInfo.tweetUrl,
            authorName = videoInfo.authorName,
            authorUsername = videoInfo.authorUsername,
            tweetText = videoInfo.tweetText,
            thumbnailUrl = videoInfo.thumbnailUrl,
            videoUrl = variant.url,
            quality = variant.getQualityLabel(),
            bitrate = variant.bitrate,
            filePath = outputFile.absolutePath,
            state = 1 // downloading
        )
        downloadDao.insert(entity)

        // Add to active downloads
        _activeDownloads.value = _activeDownloads.value + task

        // Start download job
        val job = scope.launch {
            downloadFile(task, outputFile)
        }
        downloadJobs[taskId] = job

        return taskId
    }

    private suspend fun downloadFile(task: DownloadTask, outputFile: File) {
        try {
            updateTaskState(task.id, DownloadTaskState.DOWNLOADING)

            val request = Request.Builder()
                .url(task.url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength()
            task.totalBytes = contentLength

            body.byteStream().use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (downloadJobs[task.id]?.isActive != true) {
                            // Download cancelled
                            outputStream.flush()
                            updateTaskState(task.id, DownloadTaskState.PAUSED)
                            return
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = if (contentLength > 0) {
                            ((totalBytesRead * 100) / contentLength).toInt()
                        } else {
                            0
                        }

                        task.downloadedBytes = totalBytesRead
                        task.progress = progress
                        task.state = DownloadTaskState.DOWNLOADING

                        _downloadProgress.emit(
                            DownloadProgress(
                                taskId = task.id,
                                progress = progress,
                                downloadedBytes = totalBytesRead,
                                totalBytes = contentLength
                            )
                        )

                        // Update database
                        downloadDao.updateProgress(
                            task.id,
                            1,
                            progress,
                            outputFile.absolutePath,
                            totalBytesRead
                        )
                    }
                }
            }

            // Download completed
            task.state = DownloadTaskState.COMPLETED
            task.completedAt = System.currentTimeMillis()
            updateTaskState(task.id, DownloadTaskState.COMPLETED)

            // Update database
            downloadDao.updateProgress(
                task.id,
                2,
                100,
                outputFile.absolutePath,
                task.totalBytes
            )

            _downloadProgress.emit(
                DownloadProgress(
                    taskId = task.id,
                    progress = 100,
                    downloadedBytes = task.totalBytes,
                    totalBytes = task.totalBytes,
                    isCompleted = true,
                    filePath = outputFile.absolutePath
                )
            )

        } catch (e: Exception) {
            task.state = DownloadTaskState.FAILED
            updateTaskState(task.id, DownloadTaskState.FAILED)

            _downloadProgress.emit(
                DownloadProgress(
                    taskId = task.id,
                    error = e.message ?: "Download failed"
                )
            )
        }
    }

    fun pauseDownload(taskId: String) {
        downloadJobs[taskId]?.cancel()
        downloadJobs.remove(taskId)
        updateTaskState(taskId, DownloadTaskState.PAUSED)
    }

    fun resumeDownload(taskId: String) {
        val task = _activeDownloads.value.find { it.id == taskId } ?: return
        val file = File(task.outputPath)
        if (file.exists()) {
            file.delete()
        }

        scope.launch {
            val job = scope.launch {
                downloadFile(task, file)
            }
            downloadJobs[taskId] = job
        }
    }

    fun cancelDownload(taskId: String) {
        downloadJobs[taskId]?.cancel()
        downloadJobs.remove(taskId)

        val task = _activeDownloads.value.find { it.id == taskId }
        task?.let {
            File(it.outputPath).delete()
        }

        _activeDownloads.value = _activeDownloads.value.filter { it.id != taskId }

        scope.launch {
            downloadDao.deleteById(taskId)
        }
    }

    fun deleteDownload(taskId: String) {
        cancelDownload(taskId)
        scope.launch {
            downloadDao.deleteById(taskId)
        }
    }

    private fun updateTaskState(taskId: String, state: DownloadTaskState) {
        _activeDownloads.value = _activeDownloads.value.map {
            if (it.id == taskId) it.copy(state = state) else it
        }
    }

    fun getActiveDownloads(): List<DownloadTask> = _activeDownloads.value

    data class DownloadProgress(
        val taskId: String,
        val progress: Int = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val isCompleted: Boolean = false,
        val filePath: String? = null,
        val error: String? = null
    )
}
