package com.xvideo.downloader.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.text.DecimalFormat

object FileUtils {

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        
        return DecimalFormat("#,##0.#").format(
            size / Math.pow(1024.0, digitGroups.toDouble())
        ) + " " + units[digitGroups]
    }

    fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun getFileExtension(url: String): String {
        return MimeTypeMap.getFileExtensionFromUrl(url) ?: "mp4"
    }

    fun getMimeType(url: String): String {
        val extension = getFileExtension(url)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
    }

    fun getVideoDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "XVideoDownloader")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun deleteFile(path: String): Boolean {
        return try {
            File(path).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun fileExists(path: String): Boolean {
        return File(path).exists()
    }

    fun getFileSize(path: String): Long {
        return try {
            File(path).length()
        } catch (e: Exception) {
            0L
        }
    }

    fun getFileName(path: String): String {
        return File(path).name
    }

    fun scanMediaStore(context: Context, file: File) {
        try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Video.Media.DATA, file.absolutePath)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearDownloadDirectory(context: Context) {
        val dir = getVideoDirectory(context)
        dir.listFiles()?.forEach { it.delete() }
    }
}
