package com.xvideo.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.xvideo.downloader.data.local.database.AppDatabase
import com.xvideo.downloader.data.local.DownloadManager

class App : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var downloadManager: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize database
        database = AppDatabase.getInstance(this)

        // Initialize download manager
        downloadManager = DownloadManager(this)

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Download notification channel
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
            }
            notificationManager.createNotificationChannel(downloadChannel)

            // Foreground service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Download Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Active download notifications"
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_SERVICE = "service"

        @Volatile
        private var instance: App? = null

        fun getInstance(): App {
            return instance ?: throw IllegalStateException("App not initialized")
        }
    }
}
