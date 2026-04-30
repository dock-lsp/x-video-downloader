package com.xvideo.downloader.ui.home

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.App
import com.xvideo.downloader.R
import com.xvideo.downloader.data.model.DownloadState
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.remote.repository.VideoParseState
import com.xvideo.downloader.data.remote.repository.VideoRepository
import com.xvideo.downloader.util.UrlUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository()
    private val downloadManager = App.getInstance().downloadManager
    private val resources: Resources = application.resources

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _parseState = MutableStateFlow<VideoParseState>(VideoParseState.Idle)
    val parseState: StateFlow<VideoParseState> = _parseState.asStateFlow()

    private val _currentVideoInfo = MutableStateFlow<VideoInfo?>(null)
    val currentVideoInfo: StateFlow<VideoInfo?> = _currentVideoInfo.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _currentTaskId = MutableStateFlow<String?>(null)
    val currentTaskId: StateFlow<String?> = _currentTaskId.asStateFlow()

    fun parseUrl(url: String) {
        val normalizedUrl = UrlUtils.normalizeTwitterUrl(url.trim())

        if (!UrlUtils.isValidTwitterUrl(normalizedUrl)) {
            viewModelScope.launch {
                _toastMessage.emit(resources.getString(R.string.error_invalid_url))
            }
            return
        }

        viewModelScope.launch {
            _parseState.value = VideoParseState.Loading
            _downloadState.value = DownloadState.Parsing(normalizedUrl)

            val result = repository.parseVideoFromUrl(normalizedUrl)
            result.fold(
                onSuccess = { videoInfo ->
                    _currentVideoInfo.value = videoInfo
                    _parseState.value = VideoParseState.Success(videoInfo)
                    _downloadState.value = DownloadState.Ready(videoInfo)
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: resources.getString(R.string.error_parse_failed)
                    _parseState.value = VideoParseState.Error(errorMsg)
                    _downloadState.value = DownloadState.Error(errorMsg)
                    _toastMessage.emit("${resources.getString(R.string.error)}: $errorMsg")
                }
            )
        }
    }

    fun startDownload(variant: VideoVariant) {
        val videoInfo = _currentVideoInfo.value ?: return

        viewModelScope.launch {
            try {
                _downloadState.value = DownloadState.Parsing(videoInfo.tweetUrl)
                val taskId = downloadManager.startDownload(videoInfo, variant)
                _currentTaskId.value = taskId
            } catch (e: Exception) {
                val errorMsg = e.message ?: resources.getString(R.string.error_download_failed)
                _downloadState.value = DownloadState.Error(errorMsg)
                _toastMessage.emit("${resources.getString(R.string.error)}: $errorMsg")
            }
        }
    }

    fun playOnline(variant: VideoVariant) {
        val videoInfo = _currentVideoInfo.value ?: return
        viewModelScope.launch {
            _toastMessage.emit(resources.getString(R.string.opening_player))
        }
    }

    fun clearState() {
        _parseState.value = VideoParseState.Idle
        _downloadState.value = DownloadState.Idle
        _currentVideoInfo.value = null
        _currentTaskId.value = null
    }
}
