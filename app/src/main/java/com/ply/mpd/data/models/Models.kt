package com.ply.mpd.data.models

import kotlinx.serialization.Serializable

@Serializable
data class DashRepresentation(
    val id: String,
    val mimeType: String = "",
    val codecs: String = "",
    val bandwidth: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: String = "",
    val audioSamplingRate: String = "",
    val baseUrl: String = "",
    val isAudio: Boolean = false,
    val isVideo: Boolean = false,
    val label: String = ""
) {
    /**
     * Requirement: Every quality >= 720 is "HD", every quality < 720 is "SD".
     */
    val qualityBadge: String
        get() = if ((height ?: 0) >= 720) "HD" else "SD"

    val displayResolution: String
        get() = if (width != null && height != null) "${width}x${height}" else if (height != null) "${height}p" else "Audio"

    val displayBitrate: String
        get() = if (bandwidth > 0) "${bandwidth / 1000} kbps" else ""
}

@Serializable
data class HistoryEntry(
    val id: String,
    val title: String,
    val videoPath: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val originalFbUrl: String? = null,
    val fbVideoId: String? = null,
    val mpdContent: String? = null,
    val isDownloaded: Boolean = false
) {
    /**
     * Requirement: FB icon must ONLY show for genuine Facebook videos.
     * Never show FB icon for general/other URLs.
     */
    val isFacebook: Boolean
        get() {
            if (!originalFbUrl.isNullOrBlank()) {
                val u = originalFbUrl.lowercase()
                if (u.contains("facebook.com") || u.contains("fb.watch") || u.contains("fb.com")) {
                    return true
                }
            }
            if (!fbVideoId.isNullOrBlank()) {
                return true
            }
            val path = videoPath.lowercase()
            return path.contains("facebook.com") || path.contains("fb.watch") || path.contains("fbcdn.net")
        }

    val progressPercent: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val targetFilePath: String,
    val progress: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val qualityBadge: String = "HD",
    val resolution: String = "",
    val originalFbUrl: String? = null,
    val fbVideoId: String? = null,
    val errorMessage: String? = null
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}
