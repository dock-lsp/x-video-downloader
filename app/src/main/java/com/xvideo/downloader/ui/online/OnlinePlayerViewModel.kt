package com.xvideo.downloader.ui.online

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class OnlinePlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("online_player_prefs", Context.MODE_PRIVATE)

    private val _recentUrls = MutableStateFlow<List<RecentUrl>>(emptyList())
    val recentUrls: StateFlow<List<RecentUrl>> = _recentUrls.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    init {
        loadRecentUrls()
    }

    fun setUrl(url: String) {
        _currentUrl.value = url
    }

    fun addToHistory(url: String, title: String = "") {
        viewModelScope.launch {
            val history = _recentUrls.value.toMutableList()
            // Remove duplicate if exists
            history.removeAll { it.url == url }
            // Add to front
            history.add(0, RecentUrl(url, title.ifEmpty { extractTitleFromUrl(url) }, System.currentTimeMillis()))
            // Keep only last 20
            val trimmed = history.take(20)
            _recentUrls.value = trimmed
            saveRecentUrls(trimmed)
        }
    }

    fun removeFromHistory(url: String) {
        viewModelScope.launch {
            val history = _recentUrls.value.toMutableList()
            history.removeAll { it.url == url }
            _recentUrls.value = history
            saveRecentUrls(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _recentUrls.value = emptyList()
            prefs.edit().remove(KEY_RECENT_URLS).apply()
        }
    }

    private fun loadRecentUrls() {
        val json = prefs.getString(KEY_RECENT_URLS, null) ?: return
        try {
            val jsonArray = JSONArray(json)
            val urls = mutableListOf<RecentUrl>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                urls.add(
                    RecentUrl(
                        url = obj.getString("url"),
                        title = obj.optString("title", ""),
                        timestamp = obj.optLong("timestamp", 0)
                    )
                )
            }
            _recentUrls.value = urls
        } catch (_: Exception) {
        }
    }

    private fun saveRecentUrls(urls: List<RecentUrl>) {
        val jsonArray = JSONArray()
        for (url in urls) {
            val obj = JSONObject().apply {
                put("url", url.url)
                put("title", url.title)
                put("timestamp", url.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_URLS, jsonArray.toString()).apply()
    }

    private fun extractTitleFromUrl(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.host ?: url
        } catch (_: Exception) {
            url
        }
    }

    companion object {
        private const val KEY_RECENT_URLS = "recent_urls"
    }

    data class RecentUrl(
        val url: String,
        val title: String,
        val timestamp: Long
    )
}
