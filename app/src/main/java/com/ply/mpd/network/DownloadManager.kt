package com.ply.mpd.network

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.ply.mpd.data.models.DownloadItem
import com.ply.mpd.data.models.DownloadStatus
import com.ply.mpd.data.storage.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val appStorage = AppStorage.getInstance(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun enqueueDownload(
        title: String,
        videoUrl: String,
        audioUrl: String? = null,
        qualityBadge: String = "HD",
        resolution: String = "",
        originalFbUrl: String? = null,
        fbVideoId: String? = null
    ): DownloadItem {
        val id = "dl_${System.currentTimeMillis()}"
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)
        val downloadDir = context.getExternalFilesDir(null) ?: context.filesDir
        val targetFile = File(downloadDir, "${sanitizedTitle}_${id}.mp4")

        val item = DownloadItem(
            id = id,
            title = title,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            targetFilePath = targetFile.absolutePath,
            progress = 0,
            status = DownloadStatus.DOWNLOADING,
            qualityBadge = qualityBadge,
            resolution = resolution,
            originalFbUrl = originalFbUrl,
            fbVideoId = fbVideoId
        )

        appStorage.saveDownload(item)
        startDownloadJob(item)
        return item
    }

    fun pauseDownload(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        val item = appStorage.downloads.value.firstOrNull { it.id == id }
        if (item != null) {
            appStorage.saveDownload(item.copy(status = DownloadStatus.PAUSED))
        }
    }

    fun resumeDownload(id: String) {
        val item = appStorage.downloads.value.firstOrNull { it.id == id }
        if (item != null) {
            val updated = item.copy(status = DownloadStatus.DOWNLOADING)
            appStorage.saveDownload(updated)
            startDownloadJob(updated)
        }
    }

    fun cancelDownload(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        val item = appStorage.downloads.value.firstOrNull { it.id == id }
        if (item != null) {
            try {
                File(item.targetFilePath).delete()
                File("${item.targetFilePath}.part").delete()
                File("${item.targetFilePath}.video.part").delete()
                File("${item.targetFilePath}.audio.part").delete()
            } catch (_: Exception) {}
            appStorage.deleteDownload(id)
        }
    }

    private fun startDownloadJob(item: DownloadItem) {
        activeJobs[item.id]?.cancel()

        val job = scope.launch {
            try {
                if (item.audioUrl.isNullOrBlank()) {
                    // Single progressive stream (MP4)
                    downloadSingleStream(item)
                } else {
                    // Split DASH video + audio stream, download both and mux
                    downloadDashStreamsAndMux(item)
                }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Download failed for ${item.id}", e)
                appStorage.saveDownload(
                    item.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Download failed"
                    )
                )
            } finally {
                activeJobs.remove(item.id)
            }
        }
        activeJobs[item.id] = job
    }

    /**
     * Download a file with HTTP Range resume support and token auto-refresh on 403/410
     */
    private suspend fun downloadFileWithResume(
        initialUrl: String,
        destFile: File,
        originalFbUrl: String?,
        fbVideoId: String?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        var currentUrl = initialUrl
        var totalBytes = -1L
        var retryCount = 0
        val maxRetries = 6

        while (retryCount < maxRetries) {
            val existingBytes = if (destFile.exists()) destFile.length() else 0L

            try {
                // Check if current URL is expired before even attempting
                if (FacebookExtractor.isUrlExpired(currentUrl)) {
                    val refreshed = refreshToken(originalFbUrl, fbVideoId)
                    if (refreshed != null) {
                        currentUrl = refreshed
                    }
                }

                val reqBuilder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")

                if (existingBytes > 0) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                val response = client.newCall(reqBuilder.build()).execute()

                // If 403 Forbidden or 410 Gone, token likely expired midway!
                if (response.code == 403 || response.code == 410) {
                    response.close()
                    Log.w("DownloadManager", "HTTP ${response.code} received. Attempting token refresh...")
                    val refreshed = refreshToken(originalFbUrl, fbVideoId)
                    if (refreshed != null) {
                        currentUrl = refreshed
                        retryCount++
                        delay(1000)
                        continue
                    } else {
                        throw IllegalStateException("Stream URL expired and could not be refreshed (HTTP ${response.code})")
                    }
                }

                if (!response.isSuccessful && response.code != 206) {
                    response.close()
                    throw IllegalStateException("Server returned HTTP ${response.code}")
                }

                val isPartial = response.code == 206
                val contentLength = response.body?.contentLength() ?: -1L

                totalBytes = if (isPartial) {
                    existingBytes + contentLength
                } else {
                    contentLength
                }

                val bodyStream: InputStream = response.body?.byteStream()
                    ?: throw IllegalStateException("Empty response body")

                val raf = RandomAccessFile(destFile, "rw")
                if (isPartial) {
                    raf.seek(existingBytes)
                } else {
                    raf.setLength(0)
                    raf.seek(0)
                }

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var currentBytes = if (isPartial) existingBytes else 0L

                bodyStream.use { input ->
                    raf.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            currentBytes += bytesRead
                            onProgress(currentBytes, totalBytes)
                        }
                    }
                }

                // If finished cleanly, break out of retry loop
                return

            } catch (e: Exception) {
                Log.w("DownloadManager", "Transient error during download chunk: ${e.message}, retry: $retryCount")
                retryCount++
                if (retryCount >= maxRetries) {
                    throw e
                }
                delay(2000L * retryCount)
            }
        }
    }

    private suspend fun refreshToken(originalFbUrl: String?, fbVideoId: String?): String? {
        val target = originalFbUrl ?: if (!fbVideoId.isNullOrBlank()) "https://www.facebook.com/watch/?v=$fbVideoId" else null
        if (target.isNullOrBlank()) return null
        return try {
            val result = FacebookExtractor.resolveAndExtract(target)
            result.directStreamUrl ?: result.mpdContent?.let {
                DashParser.parse(it).firstOrNull { r -> r.isVideo && r.baseUrl.isNotBlank() }?.baseUrl
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun downloadSingleStream(item: DownloadItem) {
        val partFile = File("${item.targetFilePath}.part")
        val finalFile = File(item.targetFilePath)

        downloadFileWithResume(
            initialUrl = item.videoUrl,
            destFile = partFile,
            originalFbUrl = item.originalFbUrl,
            fbVideoId = item.fbVideoId
        ) { bytesRead, totalBytes ->
            val progress = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
            appStorage.saveDownload(
                item.copy(
                    progress = progress,
                    bytesDownloaded = bytesRead,
                    totalBytes = totalBytes,
                    status = DownloadStatus.DOWNLOADING
                )
            )
        }

        partFile.renameTo(finalFile)
        appStorage.saveDownload(
            item.copy(
                progress = 100,
                bytesDownloaded = finalFile.length(),
                totalBytes = finalFile.length(),
                status = DownloadStatus.COMPLETED
            )
        )
    }

    private suspend fun downloadDashStreamsAndMux(item: DownloadItem) = withContext(Dispatchers.IO) {
        val videoPart = File("${item.targetFilePath}.video.part")
        val audioPart = File("${item.targetFilePath}.audio.part")
        val finalFile = File(item.targetFilePath)

        var videoBytes = 0L
        var videoTotal = 1L
        var audioBytes = 0L
        var audioTotal = 1L

        // 1. Download Video Stream
        downloadFileWithResume(
            initialUrl = item.videoUrl,
            destFile = videoPart,
            originalFbUrl = item.originalFbUrl,
            fbVideoId = item.fbVideoId
        ) { bytesRead, totalBytes ->
            videoBytes = bytesRead
            videoTotal = if (totalBytes > 0) totalBytes else videoTotal
            val overallProgress = ((videoBytes * 80) / videoTotal).toInt().coerceIn(0, 80)
            appStorage.saveDownload(
                item.copy(
                    progress = overallProgress,
                    bytesDownloaded = videoBytes + audioBytes,
                    totalBytes = videoTotal + audioTotal,
                    status = DownloadStatus.DOWNLOADING
                )
            )
        }

        // 2. Download Audio Stream
        if (!item.audioUrl.isNullOrBlank()) {
            downloadFileWithResume(
                initialUrl = item.audioUrl,
                destFile = audioPart,
                originalFbUrl = item.originalFbUrl,
                fbVideoId = item.fbVideoId
            ) { bytesRead, totalBytes ->
                audioBytes = bytesRead
                audioTotal = if (totalBytes > 0) totalBytes else audioTotal
                val overallProgress = (80 + ((audioBytes * 15) / audioTotal).toInt()).coerceIn(80, 95)
                appStorage.saveDownload(
                    item.copy(
                        progress = overallProgress,
                        bytesDownloaded = videoBytes + audioBytes,
                        totalBytes = videoTotal + audioTotal,
                        status = DownloadStatus.DOWNLOADING
                    )
                )
            }
        }

        // 3. Multiplex video and audio into final MP4 using MediaMuxer with interleaved samples
        appStorage.saveDownload(item.copy(progress = 96, status = DownloadStatus.DOWNLOADING))
        muxStreams(videoPart, audioPart, finalFile)

        // Cleanup part files
        videoPart.delete()
        audioPart.delete()

        appStorage.saveDownload(
            item.copy(
                progress = 100,
                bytesDownloaded = finalFile.length(),
                totalBytes = finalFile.length(),
                status = DownloadStatus.COMPLETED
            )
        )
    }

    /**
     * Mux video and audio by interleaving sample buffers based on presentation timestamps
     */
    private fun muxStreams(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = if (audioFile.exists() && audioFile.length() > 0) {
            MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        } else null

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            var videoTrackIndex = -1
            var videoSourceTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoSourceTrack = i
                    videoTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            var audioTrackIndex = -1
            var audioSourceTrack = -1
            if (audioExtractor != null) {
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioSourceTrack = i
                        audioTrackIndex = muxer.addTrack(format)
                        break
                    }
                }
            }

            muxer.start()

            if (videoSourceTrack >= 0) {
                videoExtractor.selectTrack(videoSourceTrack)
            }
            if (audioExtractor != null && audioSourceTrack >= 0) {
                audioExtractor.selectTrack(audioSourceTrack)
            }

            val bufferSize = 1024 * 1024
            val videoBuf = ByteBuffer.allocate(bufferSize)
            val audioBuf = ByteBuffer.allocate(bufferSize)
            val videoBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()

            var videoDone = (videoSourceTrack < 0)
            var audioDone = (audioSourceTrack < 0 || audioExtractor == null)

            while (!videoDone || !audioDone) {
                val videoTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (!audioDone && audioExtractor != null) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!videoDone && (audioDone || videoTime <= audioTime)) {
                    videoBufferInfo.offset = 0
                    videoBufferInfo.size = videoExtractor.readSampleData(videoBuf, 0)
                    if (videoBufferInfo.size < 0) {
                        videoDone = true
                    } else {
                        videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        videoBufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(videoTrackIndex, videoBuf, videoBufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone && audioExtractor != null) {
                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = audioExtractor.readSampleData(audioBuf, 0)
                    if (audioBufferInfo.size < 0) {
                        audioDone = true
                    } else {
                        audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioBufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(audioTrackIndex, audioBuf, audioBufferInfo)
                        audioExtractor.advance()
                    }
                }
            }
        } finally {
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
