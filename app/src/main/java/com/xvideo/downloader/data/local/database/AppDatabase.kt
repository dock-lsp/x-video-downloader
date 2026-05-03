package com.xvideo.downloader.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xvideo.downloader.data.local.database.dao.AiConversationDao
import com.xvideo.downloader.data.local.database.dao.AiMessageDao
import com.xvideo.downloader.data.local.database.dao.DownloadHistoryDao
import com.xvideo.downloader.data.local.database.dao.GeneratedProjectDao
import com.xvideo.downloader.data.local.database.entity.AiConversationEntity
import com.xvideo.downloader.data.local.database.entity.AiMessageEntity
import com.xvideo.downloader.data.local.database.entity.DownloadHistoryEntity
import com.xvideo.downloader.data.local.database.entity.GeneratedProjectEntity

@Database(
    entities = [
        DownloadHistoryEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        GeneratedProjectEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun aiConversationDao(): AiConversationDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun generatedProjectDao(): GeneratedProjectDao

    companion object {
        private const val DATABASE_NAME = "x_video_downloader.db"

        @Volatile
        private var instance: AppDatabase? = null

        // Migration from v1 to v2: add AI tables
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_conversations` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `messageCount` INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_messages` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `conversationId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `hasCode` INTEGER NOT NULL DEFAULT 0,
                        `codeBlocks` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `generated_projects` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `directoryPath` TEXT NOT NULL,
                        `fileCount` INTEGER NOT NULL DEFAULT 0,
                        `conversationId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )"""
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
