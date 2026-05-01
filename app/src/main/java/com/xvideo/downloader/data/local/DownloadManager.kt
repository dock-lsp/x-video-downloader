package com.xvideo.downloader.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.xvideo.downloader.data.model.DownloadState
import com.xvideo.downloader.data.model.DownloadTask
import com.xvideo.downloader.data.model.DownloadTaskState
import com.xvideo.downloader.data.model.M3u8Stream
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.local.database.AppDatabase
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.remote.api.TwitterApiService
import kotlinx.coroutines.*

class DownloadManager(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
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

    private val apiService = TwitterApiService.getInstance()

    companion object {
        private const val TAG = "DownloadManager"
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

    private fun getTempDirectory(): File {
        val dir = File(getDownloadDirectory(), ".temp")
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
            state = 1
        )
        downloadDao.insert(entity)

        _activeDownloads.value = _activeDownloads.value + task

        // Determine download strategy based on URL type
        val job = scope.launch {
            if (variant.url.contains("m3u8") || videoInfo.hasM3u8Stream()) {
                downloadM3u8WithFFmpeg(task, outputFile, videoInfo)
            } else {
                downloadDirectFile(task, outputFile)
            }
        }
        downloadJobs[taskId] = job

        return taskId
    }

    /**
     * Download m3u8 stream and merge audio+video using FFmpeg.
     *
     * Flow:
     * 1. Parse m3u8 master playlist to get audio/video stream URLs
     * 2. Download video stream segments → temp video file
     * 3. Download audio stream segments → temp audio file
     * 4. Use FFmpeg to merge audio + video → final MP4
     * 5. Clean up temp files
     */
    private suspend fun downloadM3u8WithFFmpeg(task: DownloadTask, outputFile: File, videoInfo: VideoInfo) {
        val tempDir = File(getTempDirectory(), task.id)
        try {
            tempDir.mkdirs()
            updateTaskState(task.id, DownloadTaskState.DOWNLOADING)

            val m3u8Url = videoInfo.m3u8Url ?: task.url
            Log.d(TAG, "Starting m3u8 download: $m3u8Url")

            // Step 1: Parse m3u8 to get streams
            emitProgress(task.id, 5, 0, 0, "正在解析 M3U8 播放列表...")

            val streamsResult = apiService.parseM3u8Playlist(m3u8Url)
            val streams = streamsResult.getOrNull()
            if (streams.isNullOrEmpty()) {
                throw Exception("无法解析 M3U8 播放列表")
            }

            // Pick the best stream (highest bandwidth)
            val bestStream = streams.first()
            Log.d(TAG, "Selected stream: ${bestStream.quality} (${bestStream.resolution})")

            // Step 2: Download video stream
            emitProgress(task.id, 10, 0, 0, "正在下载视频流...")

            val videoTempFile = File(tempDir, "video.ts")
            downloadStreamToFile(bestStream.videoUrl, videoTempFile, task.id, 10, 50)

            if (downloadJobs[task.id]?.isActive != true) {
                updateTaskState(task.id, DownloadTaskState.PAUSED)
                return
            }

            // Step 3: Download audio stream (if separate)
            val audioTempFile = if (bestStream.audioUrl != null) {
                emitProgress(task.id, 55, 0, 0, "正在下载音频流...")
                val audioFile = File(tempDir, "audio.ts")
                downloadStreamToFile(bestStream.audioUrl, audioFile, task.id, 55, 75)

                if (downloadJobs[task.id]?.isActive != true) {
                    updateTaskState(task.id, DownloadTaskState.PAUSED)
                    return
                }
                audioFile
            } else {
                null
            }

            // Step 4: FFmpeg merge
            emitProgress(task.id, 80, 0, 0, "正在合并音视频 (FFmpeg)...")

            val success = if (audioTempFile != null && audioTempFile.exists() && audioTempFile.length() > 0) {
                mergeAudioVideoWithFFmpeg(videoTempFile.absolutePath, audioTempFile.absolutePath, outputFile.absolutePath)
            } else {
                // No separate audio, just remux the video
                remuxWithFFmpeg(videoTempFile.absolutePath, outputFile.absolutePath)
            }

            if (!success) {
                throw Exception("FFmpeg 合并失败")
            }

            if (downloadJobs[task.id]?.isActive != true) {
                updateTaskState(task.id, DownloadTaskState.PAUSED)
                return
            }

            // Step 5: Complete
            task.state = DownloadTaskState.COMPLETED
            task.completedAt = System.currentTimeMillis()
            task.totalBytes = outputFile.length()
            task.downloadedBytes = outputFile.length()
            updateTaskState(task.id, DownloadTaskState.COMPLETED)

            downloadDao.updateProgress(
                task.id, 2, 100, outputFile.absolutePath, outputFile.length()
            )

            emitProgress(task.id, 100, outputFile.length(), outputFile.length(),
                isCompleted = true, filePath = outputFile.absolutePath)

            Log.d(TAG, "M3U8 download completed: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "M3U8 download failed", e)
            task.state = DownloadTaskState.FAILED
            updateTaskState(task.id, DownloadTaskState.FAILED)
            emitProgress(task.id, error = "下载失败: ${e.message}")
        } finally {
            // Clean up temp files
            tempDir.deleteRecursively()
        }
    }

    /**
     * Download an HLS stream (m3u8 playlist with segments) to a single file.
     * Supports both master playlists (recursive) and media playlists (segment lists).
     */
    private suspend fun downloadStreamToFile(
        streamUrl: String,
        outputFile: File,
        taskId: String,
        progressStart: Int,
        progressEnd: Int
    ) {
        val request = Request.Builder()
            .url(streamUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch stream: ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Empty stream response")

        // Check if this is a master playlist or media playlist
        if (body.contains("#EXT-X-STREAM-INF")) {
            // Master playlist - pick the best quality and recurse
            val lines = body.lines()
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    if (i + 1 < lines.size) {
                        val subUrl = resolveUrl(lines[i + 1].trim(), streamUrl.substringBeforeLast("/"))
                        downloadStreamToFile(subUrl, outputFile, taskId, progressStart, progressEnd)
                        return
                    }
                }
            }
            throw Exception("No streams found in master playlist")
        }

        // Media playlist - download segments
        val segments = extractSegmentUrls(body, streamUrl.substringBeforeLast("/"))
        if (segments.isEmpty()) {
            throw Exception("No segments found in playlist")
        }

        FileOutputStream(outputFile).use { outputStream ->
            for ((index, segmentUrl) in segments.withIndex()) {
                if (downloadJobs[taskId]?.isActive != true) return

                val segRequest = Request.Builder()
                    .url(segmentUrl)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val segResponse = okHttpClient.newCall(segRequest).execute()
                if (segResponse.isSuccessful) {
                    segResponse.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (downloadJobs[taskId]?.isActive != true) return
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }

                val progress = progressStart + ((index + 1) * (progressEnd - progressStart) / segments.size)
                emitProgress(taskId, progress, 0, 0, "下载中... ${index + 1}/${segments.size}")
            }
        }
    }

    private fun extractSegmentUrls(content: String, basePrefix: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segments.add(resolveUrl(trimmed, basePrefix))
            }
        }
        return segments
    }

    private fun resolveUrl(url: String, basePrefix: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$basePrefix/$url"
        }
    }

    /**
     * Merge separate audio and video files into a single MP4 using FFmpeg.
     * Command: ffmpeg -i video.ts -i audio.ts -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 output.mp4
     */
    private suspend fun mergeAudioVideoWithFFmpeg(
        videoPath: String,
        audioPath: String,
        outputPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = "-y -i \"$videoPath\" -i \"$audioPath\" -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 -movflags +faststart \"$outputPath\""
            Log.d(TAG, "FFmpeg merge command: $command")

            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode)) {
                Log.d(TAG, "FFmpeg merge succeeded")
                true
            } else {
                Log.e(TAG, "FFmpeg merge failed: ${session.failStackTrace}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg merge exception", e)
            false
        }
    }

    /**
     * Remux a single TS/MP4 file to MP4 container using FFmpeg.
     * Command: ffmpeg -i input.ts -c copy -movflags +faststart output.mp4
     */
    private suspend fun remuxWithFFmpeg(inputPath: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = "-y -i \"$inputPath\" -c copy -movflags +faststart \"$outputPath\""
            Log.d(TAG, "FFmpeg remux command: $command")

            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode)) {
                Log.d(TAG, "FFmpeg remux succeeded")
                true
            } else {
                Log.e(TAG, "FFmpeg remux failed: ${session.failStackTrace}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg remux exception", e)
            false
        }
    }

    // ==================== Direct Download (MP4 fallback) ====================

    private suspend fun downloadDirectFile(task: DownloadTask, outputFile: File) {
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
                    var lastEmitTime = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (downloadJobs[task.id]?.isActive != true) {
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

                        // Throttle progress updates to every 200ms
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > 200) {
                            lastEmitTime = now
                            emitProgress(task.id, progress, totalBytesRead, contentLength)
                        }

                        downloadDao.updateProgress(task.id, 1, progress, outputFile.absolutePath, totalBytesRead)
                    }
                }
            }

            // Completed
            task.state = DownloadTaskState.COMPLETED
            task.completedAt = System.currentTimeMillis()
            updateTaskState(task.id, DownloadTaskState.COMPLETED)

            downloadDao.updateProgress(task.id, 2, 100, outputFile.absolutePath, task.totalBytes)

            emitProgress(task.id, 100, task.totalBytes, task.totalBytes,
                isCompleted = true, filePath = outputFile.absolutePath)

        } catch (e: Exception) {
            task.state = DownloadTaskState.FAILED
            updateTaskState(task.id, DownloadTaskState.FAILED)
            emitProgress(task.id, error = e.message ?: "Download failed")
        }
    }

    // ==================== Controls ====================

    fun pauseDownload(taskId: String) {
        downloadJobs[taskId]?.cancel()
        downloadJobs.remove(taskId)
        updateTaskState(taskId, DownloadTaskState.PAUSED)
    }

    fun resumeDownload(taskId: String) {
        val task = _activeDownloads.value.find { it.id == taskId } ?: return
        val file = File(task.outputPath)
        if (file.exists()) file.delete()

        scope.launch {
            val job = scope.launch {
                if (task.url.contains("m3u8")) {
                    val videoInfo = task.videoInfo
                    downloadM3u8WithFFmpeg(task, file, videoInfo)
                } else {
                    downloadDirectFile(task, file)
                }
            }
            downloadJobs[taskId] = job
        }
    }

    fun cancelDownload(taskId: String) {
        downloadJobs[taskId]?.cancel()
        downloadJobs.remove(taskId)

        val task = _activeDownloads.value.find { it.id == taskId }
        task?.let { File(it.outputPath).delete() }

        _activeDownloads.value = _activeDownloads.value.filter { it.id != taskId }

        // Clean up temp files
        File(getTempDirectory(), taskId).deleteRecursively()

        scope.launch { downloadDao.deleteById(taskId) }
    }

    fun deleteDownload(taskId: String) {
        cancelDownload(taskId)
        scope.launch { downloadDao.deleteById(taskId) }
    }

    private fun updateTaskState(taskId: String, state: DownloadTaskState) {
        _activeDownloads.value = _activeDownloads.value.map {
            if (it.id == taskId) it.copy(state = state) else it
        }
    }

    private suspend fun emitProgress(
        taskId: String,
        progress: Int,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
        statusText: String? = null,
        isCompleted: Boolean = false,
        filePath: String? = null,
        error: String? = null
    ) {
        _downloadProgress.emit(
            DownloadProgress(
                taskId = taskId,
                progress = progress,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                statusText = statusText,
                isCompleted = isCompleted,
                filePath = filePath,
                error = error
            )
        )
    }

    fun getActiveDownloads(): List<DownloadTask> = _activeDownloads.value

    data class DownloadProgress(
        val taskId: String,
        val progress: Int = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val statusText: String? = null,
        val isCompleted: Boolean = false,
        val filePath: String? = null,
        val error: String? = null
    )
}
