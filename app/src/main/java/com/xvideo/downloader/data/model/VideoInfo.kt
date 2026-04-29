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
}

@Parcelize
data class VideoVariant(
    val url: String,
    val bitrate: Int,
    val contentType: String
) : Parcelable {

    fun getQualityLabel(): String {
        return when {
            bitrate >= 2000000 -> "4K"
            bitrate >= 1000000 -> "2K"
            bitrate >= 600000 -> "HD"
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
