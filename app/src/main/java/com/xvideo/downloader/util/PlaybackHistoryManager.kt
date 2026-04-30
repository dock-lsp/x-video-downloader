package com.xvideo.downloader.util

import android.content.Context
import android.content.SharedPreferences
import com.xvideo.downloader.data.model.PlaybackHistory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaybackHistoryManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val maxRecords = 100

    fun addHistory(title: String, uri: String, duration: Long = 0) {
        val historyList = getHistory().toMutableList()

        // Remove existing entry with same URI if exists
        historyList.removeAll { it.uri == uri }

        // Add new entry at the beginning
        val newEntry = PlaybackHistory(
            id = UUID.randomUUID().toString(),
            title = title.ifEmpty { "Unknown Video" },
            uri = uri,
            timestamp = System.currentTimeMillis(),
            duration = duration
        )
        historyList.add(0, newEntry)

        // Keep only the latest 100 records
        val trimmedList = historyList.take(maxRecords)

        // Save to SharedPreferences
        saveHistory(trimmedList)
    }

    fun getHistory(): List<PlaybackHistory> {
        val jsonString = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<PlaybackHistory>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PlaybackHistory(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        uri = obj.getString("uri"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        duration = obj.optLong("duration", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun removeHistory(id: String) {
        val historyList = getHistory().toMutableList()
        historyList.removeAll { it.id == id }
        saveHistory(historyList)
    }

    private fun saveHistory(list: List<PlaybackHistory>) {
        val jsonArray = JSONArray()
        list.forEach { history ->
            val obj = JSONObject().apply {
                put("id", history.id)
                put("title", history.title)
                put("uri", history.uri)
                put("timestamp", history.timestamp)
                put("duration", history.duration)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "playback_history_prefs"
        private const val KEY_HISTORY = "history_list"

        @Volatile
        private var instance: PlaybackHistoryManager? = null

        fun getInstance(context: Context): PlaybackHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
