package com.xvideo.downloader.ui.local

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalVideosViewModel(application: Application) : AndroidViewModel(application) {

    private val _videos = MutableStateFlow<List<LocalVideo>>(emptyList())
    val videos: StateFlow<List<LocalVideo>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    init {
        loadVideos()
    }

    fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val videoList = withContext(Dispatchers.IO) {
                    scanLocalVideos()
                }
                _videos.value = sortVideos(videoList, _sortOrder.value)
            } catch (e: Exception) {
                _toastMessage.emit("加载视频失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun scanLocalVideos(): List<LocalVideo> {
        val videos = mutableListOf<LocalVideo>()
        val context = getApplication<Application>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        // Filter out .ts segment files from MediaStore query
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?"
        val selectionArgs = arrayOf("%.ts")

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC LIMIT 500"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val path = cursor.getString(pathColumn)
                val size = cursor.getLong(sizeColumn)
                val duration = cursor.getLong(durationColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                videos.add(
                    LocalVideo(
                        id = id,
                        name = name,
                        path = path,
                        uri = contentUri,
                        size = size,
                        duration = duration,
                        dateAdded = dateAdded,
                        width = width,
                        height = height
                    )
                )
            }
        }

        // Also scan app-specific directory
        scanAppDirectory(videos)

        return videos
    }

    private fun scanAppDirectory(videos: MutableList<LocalVideo>) {
        val context = getApplication<Application>()
        val downloadDir = com.xvideo.downloader.util.FileUtils.getVideoDirectory(context)

        downloadDir.listFiles()?.filter { it.isFile && it.extension == "mp4" && !it.name.endsWith(".ts", ignoreCase = true) }?.forEach { file ->
            // Check if already in list
            if (videos.none { it.path == file.absolutePath }) {
                videos.add(
                    LocalVideo(
                        id = file.hashCode().toLong(),
                        name = file.name,
                        path = file.absolutePath,
                        uri = android.net.Uri.fromFile(file),
                        size = file.length(),
                        duration = 0, // Duration unknown without parsing
                        dateAdded = file.lastModified() / 1000,
                        width = 0,
                        height = 0
                    )
                )
            }
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        _videos.value = sortVideos(_videos.value, order)
    }

    private fun sortVideos(videos: List<LocalVideo>, order: SortOrder): List<LocalVideo> {
        return when (order) {
            SortOrder.DATE_DESC -> videos.sortedByDescending { it.dateAdded }
            SortOrder.DATE_ASC -> videos.sortedBy { it.dateAdded }
            SortOrder.SIZE_DESC -> videos.sortedByDescending { it.size }
            SortOrder.SIZE_ASC -> videos.sortedBy { it.size }
            SortOrder.NAME_ASC -> videos.sortedBy { it.name }
            SortOrder.NAME_DESC -> videos.sortedByDescending { it.name }
        }
    }

    fun deleteVideo(video: LocalVideo) {
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    java.io.File(video.path).delete()
                }
                if (deleted) {
                    _videos.value = _videos.value.filter { it.id != video.id }
                    _toastMessage.emit("视频已删除")
                } else {
                    _toastMessage.emit("删除失败")
                }
            } catch (e: Exception) {
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }
}

data class LocalVideo(
    val id: Long,
    val name: String,
    val path: String,
    val uri: android.net.Uri,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int
)

enum class SortOrder {
    DATE_DESC,
    DATE_ASC,
    SIZE_DESC,
    SIZE_ASC,
    NAME_ASC,
    NAME_DESC
}
