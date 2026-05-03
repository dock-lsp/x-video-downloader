package com.xvideo.downloader.data.local.database.dao

import androidx.room.*
import com.xvideo.downloader.data.local.database.entity.AiMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMessageDao {

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesByConversation(conversationId: String): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesList(conversationId: String): List<AiMessageEntity>

    @Query("SELECT * FROM ai_messages WHERE id = :id")
    suspend fun getById(id: String): AiMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AiMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AiMessageEntity>)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM ai_messages")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int
}
