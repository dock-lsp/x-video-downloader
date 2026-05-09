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

    var database: AppDatabase? = null
        private set

    var downloadManager: DownloadManager? = null
        private set

    var okHttpClient: OkHttpClient = createDefaultOkHttpClient()
        private set

    var isInitialized = false
        private set

    private fun createDefaultOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectionPool(ConnectionPool(8, 10, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: $sw")
                val crashFile = File(getExternalFilesDir(null), "crash_log.txt")
                crashFile.writeText("Crash on thread ${thread.name}:\n$sw\n")
                Log.e(TAG, "Crash logged to: ${crashFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash", e)
            }
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }

        try {
            okHttpClient = createDefaultOkHttpClient()
            Log.d(TAG, "OkHttpClient initialized with optimized settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure OkHttpClient, using defaults", e)
            okHttpClient = OkHttpClient()
        }

        try {
            database = AppDatabase.getInstance(this)
            Log.d(TAG, "Database initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize database", e)
        }

        try {
            downloadManager = DownloadManager(this)
            Log.d(TAG, "DownloadManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DownloadManager", e)
        }

        try {
            createNotificationChannels()
            Log.d(TAG, "Notification channels created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channels", e)
        }

        isInitialized = true
        Log.d(TAG, "App initialized successfully")
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
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(downloadChannel)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Download Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Active download notifications"
                setShowBadge(true)
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
            return instance ?: throw IllegalStateException("App not initialized, call create() first")
        }

        fun isInitialized(): Boolean {
            return instance?.isInitialized == true
        }
    }
}
