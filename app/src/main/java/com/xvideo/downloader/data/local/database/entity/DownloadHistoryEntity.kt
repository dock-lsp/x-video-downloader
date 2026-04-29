package com.xvideo.downloader.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey
    val id: String,
    val tweetId: String,
    val tweetUrl: String,
    val authorName: String,
    val authorUsername: String,
    val tweetText: String,
    val thumbnailUrl: String?,
    val videoUrl: String,
    val quality: String,
    val bitrate: Int,
    val filePath: String?,
    val fileSize: Long = 0,
    val duration: Long = 0,
    val state: Int = 0, // 0=pending, 1=downloading, 2=completed, 3=failed
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
