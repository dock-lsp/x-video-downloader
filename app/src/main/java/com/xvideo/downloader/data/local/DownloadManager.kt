package com.xvideo.downloader.data.local

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
        .connectTimeout(15, TimeUnit.SECONDS)
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

    private val database: AppDatabase by lazy { AppDatabase.getInstance(context) }
    private val downloadDao by lazy { database.downloadHistoryDao() }
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
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getTempDirectory(): File {
        val dir = File(getDownloadDirectory(), ".temp")
        if (!dir.exists()) dir.mkdirs()
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

        val job = scope.launch {
            if (variant.url.contains(".m3u8") || videoInfo.hasM3u8Stream()) {
                downloadM3u8Stream(task, outputFile, videoInfo)
            } else {
                downloadDirectFile(task, outputFile)
            }
        }
        downloadJobs[taskId] = job
        return taskId
    }

    /**
     * Download m3u8 HLS stream.
     *
     * Flow:
     * 1. Parse m3u8 master playlist → get best quality video + audio stream URLs
     * 2. Download video stream segments → temp video file
     * 3. Download audio stream segments → temp audio file (if separate)
     * 4. Merge audio + video using Android MediaMuxer → final MP4
     * 5. Clean up temp files
     */
    private suspend fun downloadM3u8Stream(task: DownloadTask, outputFile: File, videoInfo: VideoInfo) {
        val tempDir = File(getTempDirectory(), task.id)
        try {
            tempDir.mkdirs()
            updateTaskState(task.id, DownloadTaskState.DOWNLOADING)

            val m3u8Url = videoInfo.m3u8Url ?: task.url
            Log.d(TAG, "Starting m3u8 download: $m3u8Url")

            // Step 1: Parse m3u8
            emitProgress(task.id, 5, 0, 0, statusText = "正在解析 M3U8 播放列表...")

            val streamsResult = apiService.parseM3u8Playlist(m3u8Url)
            val streams = streamsResult.getOrNull()
            if (streams.isNullOrEmpty()) {
                throw Exception("无法解析 M3U8 播放列表")
            }

            val bestStream = streams.first()
            Log.d(TAG, "Selected stream: ${bestStream.quality} (${bestStream.resolution})")

            // Step 2: Download video stream
            emitProgress(task.id, 10, 0, 0, statusText = "正在下载视频流...")

            val videoTempFile = File(tempDir, "video.ts")
            downloadHlsStream(bestStream.videoUrl, videoTempFile, task.id, 10, 55)

            if (downloadJobs[task.id]?.isActive != true) {
                updateTaskState(task.id, DownloadTaskState.PAUSED)
                return
            }

            // Step 3: Download audio stream (if separate)
            val audioTempFile = if (bestStream.audioUrl != null) {
                emitProgress(task.id, 60, 0, 0, statusText = "正在下载音频流...")
                val audioFile = File(tempDir, "audio.ts")
                downloadHlsStream(bestStream.audioUrl, audioFile, task.id, 60, 80)

                if (downloadJobs[task.id]?.isActive != true) {
                    updateTaskState(task.id, DownloadTaskState.PAUSED)
                    return
                }
                audioFile
            } else null

            // Step 4: Merge with MediaMuxer
            emitProgress(task.id, 85, 0, 0, statusText = "正在合并音视频...")

            val success = if (audioTempFile != null && audioTempFile.exists() && audioTempFile.length() > 0) {
                mergeAudioVideo(videoTempFile.absolutePath, audioTempFile.absolutePath, outputFile.absolutePath)
            } else {
                remuxToMp4(videoTempFile.absolutePath, outputFile.absolutePath)
            }

            if (!success) {
                // Fallback: just copy the TS file as-is (ExoPlayer can still play it)
                Log.w(TAG, "MediaMuxer merge failed, copying raw file")
                videoTempFile.copyTo(outputFile, overwrite = true)
            }

            // Step 5: Complete
            completeDownload(task, outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "M3U8 download failed", e)
            task.state = DownloadTaskState.FAILED
            updateTaskState(task.id, DownloadTaskState.FAILED)
            emitProgress(task.id, error = "下载失败: ${e.message}")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Download HLS stream (m3u8 playlist with TS segments) to a single file.
     */
    private suspend fun downloadHlsStream(
        streamUrl: String, outputFile: File, taskId: String,
        progressStart: Int, progressEnd: Int
    ) {
        val request = Request.Builder().url(streamUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("获取流失败: ${response.code}")

        val body = response.body?.string() ?: throw Exception("流响应为空")
        val basePrefix = streamUrl.substringBeforeLast("/")

        // If this is a master playlist, pick the best quality sub-stream
        if (body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines()
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    if (i + 1 < lines.size) {
                        val subUrl = resolveUrl(lines[i + 1].trim(), basePrefix)
                        downloadHlsStream(subUrl, outputFile, taskId, progressStart, progressEnd)
                        return
                    }
                }
            }
            throw Exception("主播放列表中未找到流")
        }

        // Media playlist - download segments
        val segments = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveUrl(it, basePrefix) }

        if (segments.isEmpty()) throw Exception("播放列表中未找到分片")

        FileOutputStream(outputFile).use { outputStream ->
            for ((index, segmentUrl) in segments.withIndex()) {
                if (downloadJobs[taskId]?.isActive != true) return

                val segRequest = Request.Builder().url(segmentUrl)
                    .addHeader("User-Agent", "Mozilla/5.0").build()

                val segResponse = okHttpClient.newCall(segRequest).execute()
                if (segResponse.isSuccessful) {
                    segResponse.body?.byteStream()?.use { input ->
                        input.copyTo(outputStream)
                    }
                }

                val progress = progressStart + ((index + 1) * (progressEnd - progressStart) / segments.size)
                emitProgress(taskId, progress, 0, 0, statusText = "下载中... ${index + 1}/${segments.size}")
            }
        }
    }

    /**
     * Merge separate audio and video TS files into MP4 using Android MediaMuxer.
     * No FFmpeg dependency needed - uses built-in Android APIs.
     */
    private suspend fun mergeAudioVideo(
        videoPath: String, audioPath: String, outputPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoPath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioPath) }

            // Find video and audio tracks
            val videoTrackIndex = findTrack(videoExtractor, "video/")
            val audioTrackIndex = findTrack(audioExtractor, "audio/")

            if (videoTrackIndex < 0) {
                Log.e(TAG, "No video track found")
                return@withContext false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            var muxerAudioTrack = -1
            if (audioTrackIndex >= 0) {
                audioExtractor.selectTrack(audioTrackIndex)
                val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            // Write video samples
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            writeSamples(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)

            // Write audio samples
            if (muxerAudioTrack >= 0) {
                writeSamples(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)
            }

            Log.d(TAG, "MediaMuxer merge succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer merge failed", e)
            false
        } finally {
            videoExtractor?.release()
            audioExtractor?.release()
            try { muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }

    /**
     * Remux a single TS file to MP4 using MediaMuxer.
     */
    private suspend fun remuxToMp4(inputPath: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor().apply { setDataSource(inputPath) }
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackCount = extractor.trackCount
            val muxerTracks = mutableListOf<Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    muxerTracks.add(muxer.addTrack(format))
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (i in 0 until trackCount) {
                extractor.selectTrack(i)
                writeSamples(extractor, muxer, muxerTracks[i], buffer, bufferInfo)
                extractor.unselectTrack(i)
            }

            Log.d(TAG, "MediaMuxer remux succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer remux failed", e)
            false
        } finally {
            extractor?.release()
            try { muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun writeSamples(
        extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int,
        buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo
    ) {
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    private fun resolveUrl(url: String, basePrefix: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url
        else "$basePrefix/$url"
    }

    // ==================== Direct Download (MP4) ====================

    private suspend fun downloadDirectFile(task: DownloadTask, outputFile: File) {
        try {
            updateTaskState(task.id, DownloadTaskState.DOWNLOADING)

            val request = Request.Builder().url(task.url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")

            val body = response.body ?: throw Exception("响应体为空")
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

                        val progress = if (contentLength > 0) ((totalBytesRead * 100) / contentLength).toInt() else 0
                        task.downloadedBytes = totalBytesRead
                        task.progress = progress

                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime > 200) {
                            lastEmitTime = now
                            emitProgress(task.id, progress, totalBytesRead, contentLength)
                        }

                        downloadDao.updateProgress(task.id, 1, progress, outputFile.absolutePath, totalBytesRead)
                    }
                }
            }

            completeDownload(task, outputFile)

        } catch (e: Exception) {
            task.state = DownloadTaskState.FAILED
            updateTaskState(task.id, DownloadTaskState.FAILED)
            emitProgress(task.id, error = e.message ?: "下载失败")
        }
    }

    private suspend fun completeDownload(task: DownloadTask, outputFile: File) {
        task.state = DownloadTaskState.COMPLETED
        task.completedAt = System.currentTimeMillis()
        task.totalBytes = outputFile.length()
        task.downloadedBytes = outputFile.length()
        updateTaskState(task.id, DownloadTaskState.COMPLETED)

        downloadDao.updateProgress(task.id, 2, 100, outputFile.absolutePath, outputFile.length())
        emitProgress(task.id, 100, outputFile.length(), outputFile.length(),
            isCompleted = true, filePath = outputFile.absolutePath)

        // Scan media store so it shows up in gallery
        com.xvideo.downloader.util.FileUtils.scanMediaStore(context, outputFile)
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
                if (task.url.contains(".m3u8") || task.videoInfo.hasM3u8Stream()) {
                    downloadM3u8Stream(task, file, task.videoInfo)
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
        taskId: String, progress: Int, downloadedBytes: Long = 0, totalBytes: Long = 0,
        statusText: String? = null, isCompleted: Boolean = false,
        filePath: String? = null, error: String? = null
    ) {
        _downloadProgress.emit(DownloadProgress(
            taskId = taskId, progress = progress,
            downloadedBytes = downloadedBytes, totalBytes = totalBytes,
            statusText = statusText, isCompleted = isCompleted,
            filePath = filePath, error = error
        ))
    }

    fun getActiveDownloads(): List<DownloadTask> = _activeDownloads.value

    data class DownloadProgress(
        val taskId: String, val progress: Int = 0,
        val downloadedBytes: Long = 0, val totalBytes: Long = 0,
        val statusText: String? = null, val isCompleted: Boolean = false,
        val filePath: String? = null, val error: String? = null
    )
}
