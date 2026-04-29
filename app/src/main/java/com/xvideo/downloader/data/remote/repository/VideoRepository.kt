package com.xvideo.downloader.data.remote.repository

import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.remote.api.TwitterApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class VideoRepository(
    private val twitterApiService: TwitterApiService = TwitterApiService.getInstance()
) {

    suspend fun parseVideoFromUrl(url: String): Result<VideoInfo> {
        return twitterApiService.parseTweetUrl(url)
    }

    fun parseVideoFromUrlFlow(url: String): Flow<VideoParseState> = flow {
        emit(VideoParseState.Loading)

        val result = twitterApiService.parseTweetUrl(url)
        result.fold(
            onSuccess = { videoInfo ->
                emit(VideoParseState.Success(videoInfo))
            },
            onFailure = { error ->
                emit(VideoParseState.Error(error.message ?: "Failed to parse video"))
            }
        )
    }

    fun isValidTwitterUrl(url: String): Boolean {
        val patterns = listOf(
            Regex("twitter\\.com/\\w+/status/\\d+"),
            Regex("x\\.com/\\w+/status/\\d+"),
            Regex("twitter\\.com/\\w+/status/\\w+"),
            Regex("x\\.com/\\w+/status/\\w+")
        )
        return patterns.any { it.containsMatchIn(url) }
    }
}

sealed class VideoParseState {
    object Idle : VideoParseState()
    object Loading : VideoParseState()
    data class Success(val videoInfo: VideoInfo) : VideoParseState()
    data class Error(val message: String) : VideoParseState()
}
