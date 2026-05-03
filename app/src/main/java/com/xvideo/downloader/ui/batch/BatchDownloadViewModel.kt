package com.xvideo.downloader.ui.batch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.App
import com.xvideo.downloader.data.local.DownloadManager
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.remote.repository.VideoRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BatchResultItem(
    val url: String,
    val statusText: String = "等待中...",
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val isError: Boolean = false
)

sealed class BatchDownloadState {
    object Idle : BatchDownloadState()
    data class Processing(
        val total: Int,
        val completed: Int,
        val results: List<BatchResultItem>
    ) : BatchDownloadState()
    data class Done(
        val total: Int,
        val successCount: Int,
        val failCount: Int,
        val results: List<BatchResultItem>
    ) : BatchDownloadState()
}

class BatchDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository()
    private val downloadManager: DownloadManager = try {
        App.getInstance().downloadManager
    } catch (_: Exception) {
        DownloadManager(application)
    }

    private val _batchState = MutableStateFlow<BatchDownloadState>(BatchDownloadState.Idle)
    val batchState: StateFlow<BatchDownloadState> = _batchState.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    fun startBatchDownload(urls: List<String>) {
        viewModelScope.launch {
            val results = mutableListOf<BatchResultItem>()
            var successCount = 0
            var failCount = 0

            // Initialize all items
            urls.forEach { url ->
                results.add(BatchResultItem(url = url, statusText = "等待中..."))
            }
            _batchState.value = BatchDownloadState.Processing(urls.size, 0, results.toList())

            for ((index, url) in urls.withIndex()) {
                // Update current item to loading
                results[index] = results[index].copy(isLoading = true, statusText = "正在解析...")
                _batchState.value = BatchDownloadState.Processing(
                    urls.size, index, results.toList()
                )

                try {
                    val result = repository.parseVideoFromUrl(url)
                    result.fold(
                        onSuccess = { videoInfo ->
                            val bestVariant = videoInfo.getBestQualityVideo()
                            if (bestVariant != null) {
                                downloadManager.startDownload(videoInfo, bestVariant)
                                results[index] = results[index].copy(
                                    isLoading = false,
                                    isSuccess = true,
                                    statusText = "已添加到下载"
                                )
                                successCount++
                            } else {
                                results[index] = results[index].copy(
                                    isLoading = false,
                                    isError = true,
                                    statusText = "未找到视频"
                                )
                                failCount++
                            }
                        },
                        onFailure = { error ->
                            results[index] = results[index].copy(
                                isLoading = false,
                                isError = true,
                                statusText = "失败: ${error.message ?: "未知错误"}"
                            )
                            failCount++
                        }
                    )
                } catch (e: Exception) {
                    results[index] = results[index].copy(
                        isLoading = false,
                        isError = true,
                        statusText = "错误: ${e.message}"
                    )
                    failCount++
                }
            }

            _batchState.value = BatchDownloadState.Done(
                urls.size, successCount, failCount, results.toList()
            )

            if (successCount > 0) {
                _toastMessage.emit("已添加 $successCount 个下载任务")
            }
        }
    }

    fun reset() {
        _batchState.value = BatchDownloadState.Idle
    }
}
