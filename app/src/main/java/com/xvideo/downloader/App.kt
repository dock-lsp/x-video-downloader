package com.xvideo.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.xvideo.downloader.data.local.database.AppDatabase
import com.xvideo.downloader.data.local.DownloadManager
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
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

        // Global crash handler — writes crash to file for debugging
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Log.e(TAG, "Uncaught exception: $sw")
                val crashFile = File(getExternalFilesDir(null), "crash_log.txt")
                crashFile.writeText("Crash on thread ${thread.name}:\n$sw\n")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        instance = this

        // Initialize OkHttpClient first (least likely to fail)
        okHttpClient = try {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "OkHttpClient init failed", e)
            OkHttpClient()
        }

        // Initialize database with error handling
        database = try {
            AppDatabase.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "Database init failed", e)
            // Retry — fallbackToDestructiveMigration should handle it
            AppDatabase.getInstance(this)
        }

        // Initialize download manager
        downloadManager = try {
            DownloadManager(this)
        } catch (e: Exception) {
            Log.e(TAG, "DownloadManager init failed", e)
            DownloadManager(this)
        }

        // Create notification channels
        try {
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e(TAG, "Notification channels failed", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
            }
            notificationManager.createNotificationChannel(downloadChannel)

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
        private const val TAG = "XVideoApp"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_SERVICE = "service"

        @Volatile
        private var instance: App? = null

        fun getInstance(): App {
            return instance ?: throw IllegalStateException("App not initialized")
        }
    }
}
