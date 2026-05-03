package com.xvideo.downloader.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_messages")
data class AiMessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val hasCode: Boolean = false,
    val codeBlocks: String? = null, // JSON array of code blocks
    val createdAt: Long = System.currentTimeMillis()
)
