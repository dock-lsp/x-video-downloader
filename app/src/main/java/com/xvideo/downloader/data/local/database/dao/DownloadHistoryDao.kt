package com.xvideo.downloader.data.local.database.dao

import androidx.room.*
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY createdAt DESC")
    fun getAllHistory(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM download_history WHERE state = 2 ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM download_history WHERE state IN (0, 1) ORDER BY createdAt DESC")
    fun getActiveDownloads(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getById(id: String): DownloadHistoryEntity?

    @Query("SELECT * FROM download_history WHERE tweetUrl = :url")
    suspend fun getByUrl(url: String): DownloadHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadHistoryEntity)

    @Update
    suspend fun update(entity: DownloadHistoryEntity)

    @Delete
    suspend fun delete(entity: DownloadHistoryEntity)

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_history")
    suspend fun clearAll()

    @Query("UPDATE download_history SET state = :state, progress = :progress, filePath = :filePath, fileSize = :fileSize WHERE id = :id")
    suspend fun updateProgress(id: String, state: Int, progress: Int, filePath: String?, fileSize: Long)

    @Query("SELECT fileSize FROM download_history WHERE id = :id")
    suspend fun getDownloadedBytes(id: String): Long?
}
