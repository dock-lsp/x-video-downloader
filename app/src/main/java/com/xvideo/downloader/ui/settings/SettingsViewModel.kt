package com.xvideo.downloader.ui.settings

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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

    private val _speedLimit = MutableStateFlow(getSpeedLimit())
    val speedLimit: StateFlow<Long> = _speedLimit.asStateFlow()

    private val _appLanguage = MutableStateFlow(getAppLanguage())
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

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

    private fun getSpeedLimit(): Long {
        return prefs.getLong("speed_limit", 0L)
    }

    private fun getAppLanguage(): String {
        return prefs.getString("app_language", LANG_SYSTEM) ?: LANG_SYSTEM
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

    fun setSpeedLimit(bytesPerSecond: Long) {
        prefs.edit().putLong("speed_limit", bytesPerSecond).apply()
        _speedLimit.value = bytesPerSecond
        // Apply to download manager
        try {
            com.xvideo.downloader.App.getInstance().downloadManager.setSpeedLimit(bytesPerSecond)
        } catch (_: Exception) {}
    }

    fun setAppLanguage(language: String) {
        prefs.edit().putString("app_language", language).apply()
        _appLanguage.value = language
        
        // Set the app locale
        val localeList = when (language) {
            LANG_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LANG_CHINESE -> LocaleListCompat.forLanguageTags("zh")
            LANG_KOREAN -> LocaleListCompat.forLanguageTags("ko")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)
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
        val SPEED_LIMIT_LABELS = listOf("不限速", "1 MB/s", "2 MB/s", "5 MB/s")
        val SPEED_LIMIT_VALUES = listOf(0L, 1_048_576L, 2_097_152L, 5_242_880L)
        
        const val LANG_SYSTEM = "system"
        const val LANG_ENGLISH = "en"
        const val LANG_CHINESE = "zh"
        const val LANG_KOREAN = "ko"
        
        val LANGUAGE_OPTIONS = listOf("System Default", "English", "中文（简体）", "한국어")
        val LANGUAGE_VALUES = listOf(LANG_SYSTEM, LANG_ENGLISH, LANG_CHINESE, LANG_KOREAN)
    }
}
