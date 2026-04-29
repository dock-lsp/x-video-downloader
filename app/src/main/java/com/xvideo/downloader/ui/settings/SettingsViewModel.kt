package com.xvideo.downloader.ui.settings

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xvideo.downloader.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(getThemeMode())
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    private val _downloadPath = MutableStateFlow(getDownloadPath())
    val downloadPath: StateFlow<String> = _downloadPath.asStateFlow()

    private val _downloadQuality = MutableStateFlow(getDownloadQuality())
    val downloadQuality: StateFlow<String> = _downloadQuality.asStateFlow()

    private val _autoPlay = MutableStateFlow(getAutoPlay())
    val autoPlay: StateFlow<Boolean> = _autoPlay.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private fun getThemeMode(): Int {
        return prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun getDownloadPath(): String {
        return prefs.getString("download_path", "Default") ?: "Default"
    }

    private fun getDownloadQuality(): String {
        return prefs.getString("download_quality", "Best") ?: "Best"
    }

    private fun getAutoPlay(): Boolean {
        return prefs.getBoolean("auto_play", true)
    }

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt("theme_mode", mode).apply()
        _themeMode.value = mode
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun setDownloadPath(path: String) {
        prefs.edit().putString("download_path", path).apply()
        _downloadPath.value = path
    }

    fun setDownloadQuality(quality: String) {
        prefs.edit().putString("download_quality", quality).apply()
        _downloadQuality.value = quality
    }

    fun setAutoPlay(enabled: Boolean) {
        prefs.edit().putBoolean("auto_play", enabled).apply()
        _autoPlay.value = enabled
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                FileUtils.clearDownloadDirectory(context)
                _toastMessage.emit("Cache cleared")
            } catch (e: Exception) {
                _toastMessage.emit("Failed to clear cache")
            }
        }
    }

    fun getAppVersion(): String {
        return try {
            val context = getApplication<Application>()
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        val THEME_OPTIONS = listOf("System Default", "Light", "Dark")
        val QUALITY_OPTIONS = listOf("Best", "4K", "2K", "HD", "SD")
    }
}
