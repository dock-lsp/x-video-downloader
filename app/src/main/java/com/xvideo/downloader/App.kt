package com.xvideo.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.xvideo.downloader.data.local.database.AppDatabase
import com.xvideo.downloader.data.local.DownloadManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var downloadManager: DownloadManager
        private set

    lateinit var okHttpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            // Initialize database
            database = AppDatabase.getInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
            // Retry once after clearing potentially corrupt state
            database = try { AppDatabase.getInstance(this) } catch (e2: Exception) { throw e2 }
        }

        try {
            // Initialize shared OkHttpClient with connection pool
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
            okHttpClient = OkHttpClient()
        }

        try {
            // Initialize download manager
            downloadManager = DownloadManager(this)
        } catch (e: Exception) {
            e.printStackTrace()
            // Create a minimal download manager
            downloadManager = DownloadManager(this)
        }

        try {
            // Create notification channels
            createNotificationChannels()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
