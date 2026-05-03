package com.xvideo.downloader.data.local.database.dao

import androidx.room.*
import com.xvideo.downloader.data.local.database.entity.GeneratedProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedProjectDao {

    @Query("SELECT * FROM generated_projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<GeneratedProjectEntity>>

    @Query("SELECT * FROM generated_projects WHERE id = :id")
    suspend fun getById(id: String): GeneratedProjectEntity?

    @Query("SELECT * FROM generated_projects WHERE conversationId = :conversationId")
    suspend fun getByConversation(conversationId: String): List<GeneratedProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GeneratedProjectEntity)

    @Update
    suspend fun update(entity: GeneratedProjectEntity)

    @Query("DELETE FROM generated_projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM generated_projects")
    suspend fun clearAll()
}
