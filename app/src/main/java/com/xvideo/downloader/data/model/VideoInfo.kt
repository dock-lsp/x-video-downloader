package com.xvideo.downloader.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoInfo(
    val tweetId: String,
    val tweetUrl: String,
    val authorName: String,
    val authorUsername: String,
    val tweetText: String,
    val thumbnailUrl: String?,
    val videoVariants: List<VideoVariant>,
    val gifVariants: List<GifVariant>,
    val m3u8Url: String? = null,
    val hasM3u8: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {

    fun getBestQualityVideo(): VideoVariant? {
        return videoVariants.filter { it.contentType == "video/mp4" }
            .maxByOrNull { it.bitrate }
    }

    fun getAvailableQualities(): List<VideoVariant> {
        return videoVariants
            .filter { it.contentType == "video/mp4" }
            .sortedByDescending { it.bitrate }
    }

    fun hasGif(): Boolean = gifVariants.isNotEmpty()
    fun hasM3u8Stream(): Boolean = hasM3u8 && m3u8Url != null
}

@Parcelize
data class VideoVariant(
    val url: String,
    val bitrate: Int,
    val contentType: String
) : Parcelable {

    fun getQualityLabel(): String {
        return when {
            bitrate >= 8000000 -> "4K"
            bitrate >= 4000000 -> "2K"
            bitrate >= 2000000 -> "HD"
            else -> "SD"
        }
    }
}

@Parcelize
data class GifVariant(
    val url: String,
    val bitrate: Int
) : Parcelable

@Parcelize
data class AudioInfo(
    val url: String,
    val duration: Long
) : Parcelable

@Parcelize
data class M3u8Stream(
    val videoUrl: String,
    val audioUrl: String?,
    val bandwidth: Long,
    val resolution: String?,
    val quality: String
) : Parcelable

@Parcelize
data class PlaybackHistory(
    val id: String,
    val title: String,
    val uri: String,
    val timestamp: Long = System.currentTimeMillis(),
    val duration: Long = 0
) : Parcelable
