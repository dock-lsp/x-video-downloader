package com.xvideo.downloader.ui.downloads

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.App
import com.xvideo.downloader.data.local.DownloadManager
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.model.DownloadTask
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager: DownloadManager? = runCatching {
        App.getInstance().downloadManager
    }.onFailure { e ->
        Log.e(TAG, "Failed to get DownloadManager", e)
    }.getOrNull()

    private val downloadHistoryDao = runCatching {
        App.getInstance().database?.downloadHistoryDao()
    }.onFailure { e ->
        Log.e(TAG, "Failed to get downloadHistoryDao", e)
    }.getOrNull()

    val activeDownloads: StateFlow<List<DownloadTask>> =
        downloadManager?.activeDownloads ?: MutableStateFlow(emptyList())

    val downloadHistory: Flow<List<DownloadHistoryEntity>> =
        downloadHistoryDao?.getAllHistory() ?: flowOf(emptyList())

    val completedDownloads: Flow<List<DownloadHistoryEntity>> =
        downloadHistoryDao?.getCompletedDownloads() ?: flowOf(emptyList())

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        if (downloadManager == null) {
            Log.w(TAG, "DownloadManager not available - downloads will not work")
        }
        if (downloadHistoryDao == null) {
            Log.w(TAG, "DownloadHistoryDao not available - history will not load")
        }

        viewModelScope.launch {
            downloadManager?.downloadProgress?.collect { progress ->
                when {
                    progress.isCompleted -> {
                        _toastMessage.emit("Download completed!")
                        Log.d(TAG, "Download completed: ${progress.taskId}")
                    }
                    progress.error != null -> {
                        _toastMessage.emit("Error: ${progress.error}")
                        Log.e(TAG, "Download error: ${progress.error}")
                    }
                }
            }
        }
    }

    fun pauseDownload(taskId: String) {
        downloadManager?.pauseDownload(taskId) ?: run {
            Log.w(TAG, "Cannot pause - DownloadManager not available")
        }
    }

    fun resumeDownload(taskId: String) {
        downloadManager?.resumeDownload(taskId) ?: run {
            Log.w(TAG, "Cannot resume - DownloadManager not available")
        }
    }

    fun cancelDownload(taskId: String) {
        downloadManager?.cancelDownload(taskId) ?: run {
            Log.w(TAG, "Cannot cancel - DownloadManager not available")
        }
    }

    fun deleteDownload(taskId: String) {
        downloadManager?.deleteDownload(taskId) ?: run {
            Log.w(TAG, "Cannot delete - DownloadManager not available")
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching {
                downloadHistoryDao?.clearAll()
            }.onFailure { e ->
                Log.e(TAG, "Failed to clear history", e)
            }
        }
    }

    fun refresh() {
        // Flow-based data automatically updates
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "DownloadsViewModel cleared")
    }

    companion object {
        private const val TAG = "DownloadsViewModel"
    }
}
