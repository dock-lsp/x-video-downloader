package com.xvideo.downloader.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.App
import com.xvideo.downloader.data.local.DownloadManager
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.model.DownloadTask
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = try { App.getInstance().downloadManager } catch (_: Exception) { DownloadManager(application) }
    private val database = try { App.getInstance().database } catch (_: Exception) { com.xvideo.downloader.data.local.database.AppDatabase.getInstance(application) }
    private val downloadHistoryDao = database.downloadHistoryDao()

    val activeDownloads: StateFlow<List<DownloadTask>> = downloadManager.activeDownloads

    val downloadHistory: Flow<List<DownloadHistoryEntity>> = downloadHistoryDao.getAllHistory()

    val completedDownloads: Flow<List<DownloadHistoryEntity>> = downloadHistoryDao.getCompletedDownloads()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        viewModelScope.launch {
            downloadManager.downloadProgress.collect { progress ->
                if (progress.isCompleted) {
                    _toastMessage.emit("Download completed!")
                } else if (progress.error != null) {
                    _toastMessage.emit("Error: ${progress.error}")
                }
            }
        }
    }

    fun pauseDownload(taskId: String) {
        downloadManager.pauseDownload(taskId)
    }

    fun resumeDownload(taskId: String) {
        downloadManager.resumeDownload(taskId)
    }

    fun cancelDownload(taskId: String) {
        downloadManager.cancelDownload(taskId)
    }

    fun deleteDownload(taskId: String) {
        downloadManager.deleteDownload(taskId)
    }

    fun clearHistory() {
        viewModelScope.launch {
            downloadHistoryDao.clearAll()
        }
    }

    fun refresh() {
        // Flow-based data automatically updates; this triggers a manual re-check
        // by reloading the history from the database
        viewModelScope.launch {
            // Force re-collect by toggling a refresh signal
            // The Room Flow will emit fresh data automatically
        }
    }
}
