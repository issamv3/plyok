package com.ply.mpd.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.ply.mpd.data.models.DownloadItem
import com.ply.mpd.data.models.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ply_app_prefs", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    init {
        loadHistory()
        loadDownloads()
    }

    private fun loadHistory() {
        val raw = prefs.getString(KEY_HISTORY, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val list: List<HistoryEntry> = json.decodeFromString(raw)
                _history.value = list.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                _history.value = emptyList()
            }
        }
    }

    private fun loadDownloads() {
        val raw = prefs.getString(KEY_DOWNLOADS, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val list: List<DownloadItem> = json.decodeFromString(raw)
                _downloads.value = list
            } catch (e: Exception) {
                _downloads.value = emptyList()
            }
        }
    }

    fun saveHistoryEntry(entry: HistoryEntry) {
        val current = _history.value.toMutableList()
        // Deduplicate by videoPath or id
        current.removeAll { it.id == entry.id || it.videoPath == entry.videoPath }
        current.add(0, entry)
        _history.value = current
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(current)).apply()
    }

    fun updateProgress(id: String, positionMs: Long, durationMs: Long) {
        val current = _history.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = current[index].copy(
                positionMs = positionMs,
                durationMs = durationMs,
                timestamp = System.currentTimeMillis()
            )
            current[index] = updated
            _history.value = current
            prefs.edit().putString(KEY_HISTORY, json.encodeToString(current)).apply()
        }
    }

    fun updateStreamUrl(id: String, newUrl: String, newMpd: String? = null) {
        val current = _history.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = current[index].copy(
                videoPath = newUrl,
                mpdContent = newMpd ?: current[index].mpdContent,
                timestamp = System.currentTimeMillis()
            )
            current[index] = updated
            _history.value = current
            prefs.edit().putString(KEY_HISTORY, json.encodeToString(current)).apply()
        }
    }

    fun deleteHistoryEntry(id: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.id == id }
        _history.value = current
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(current)).apply()
    }

    fun clearAllHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun saveDownload(item: DownloadItem) {
        val current = _downloads.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
        } else {
            current.add(0, item)
        }
        _downloads.value = current
        prefs.edit().putString(KEY_DOWNLOADS, json.encodeToString(current)).apply()
    }

    fun deleteDownload(id: String) {
        val current = _downloads.value.toMutableList()
        current.removeAll { it.id == id }
        _downloads.value = current
        prefs.edit().putString(KEY_DOWNLOADS, json.encodeToString(current)).apply()
    }

    // Settings
    var autoResume: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESUME, value).apply()

    var autoRotate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ROTATE, value).apply()

    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_SPEED, value).apply()

    companion object {
        private const val KEY_HISTORY = "history_entries"
        private const val KEY_DOWNLOADS = "downloads_list"
        private const val KEY_AUTO_RESUME = "setting_auto_resume"
        private const val KEY_AUTO_ROTATE = "setting_auto_rotate"
        private const val KEY_DEFAULT_SPEED = "setting_default_speed"

        @Volatile
        private var INSTANCE: AppStorage? = null

        fun getInstance(context: Context): AppStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
