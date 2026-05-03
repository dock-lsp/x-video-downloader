package com.xvideo.downloader.data.local.database.dao

import androidx.room.*
import com.xvideo.downloader.data.local.database.entity.AiConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConversationDao {

    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    suspend fun getById(id: String): AiConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AiConversationEntity)

    @Update
    suspend fun update(entity: AiConversationEntity)

    @Query("DELETE FROM ai_conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM ai_conversations")
    suspend fun clearAll()

    @Query("UPDATE ai_conversations SET messageCount = :count, updatedAt = :time WHERE id = :id")
    suspend fun updateMessageCount(id: String, count: Int, time: Long = System.currentTimeMillis())
}
