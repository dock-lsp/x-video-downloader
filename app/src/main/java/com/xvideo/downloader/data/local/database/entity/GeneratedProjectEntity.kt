package com.xvideo.downloader.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generated_projects")
data class GeneratedProjectEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val directoryPath: String,
    val fileCount: Int = 0,
    val conversationId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
