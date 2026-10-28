package com.ply.mpd

import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Track
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator

class MainActivity : FlutterActivity() {
    private val channelName = "ply/media"
    private var channel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        channel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "mux" -> handleMux(call, result)
                "thumbnail" -> handleThumbnail(call, result)
                "getInitialIntent" -> handleGetInitialIntent(result)
                "readTextUri" -> handleReadTextUri(call, result)
                "resolveUriToFilePath" -> handleResolveUriToFilePath(call, result)
                "resolveRedirect" -> {
                    val inputUrl = call.argument<String>("url") ?: ""
                    val cookie = call.argument<String>("cookie") ?: ""
                    Thread {
                        try {
                            var currentUrl = inputUrl
                            var redirectCount = 0
                            val maxRedirects = 10
                            var lastBody = ""

                            while (redirectCount < maxRedirects) {
                                val urlObj = java.net.URL(currentUrl)
                                val conn = urlObj.openConnection() as java.net.HttpURLConnection
                                conn.instanceFollowRedirects = false
                                conn.requestMethod = "GET"
                                conn.connectTimeout = 12000
                                conn.readTimeout = 12000
                                conn.setRequestProperty(
                                    "User-Agent",
                                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                )
                                conn.setRequestProperty(
                                    "Accept",
                                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                                )
                                conn.setRequestProperty(
                                    "Accept-Language",
                                    "en-US,en;q=0.9,ar;q=0.8"
                                )
                                conn.setRequestProperty("Sec-Fetch-Dest", "document")
                                conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
                                conn.setRequestProperty("Sec-Fetch-Site", "none")
                                conn.setRequestProperty("Upgrade-Insecure-Requests", "1")

                                if (cookie.isNotEmpty()) {
                                    conn.setRequestProperty("Cookie", cookie)
                                } else {
                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                    val c1 = cookieManager.getCookie(currentUrl) ?: ""
                                    val c2 = cookieManager.getCookie("https://www.facebook.com") ?: ""
                                    val finalC = if (c1.isNotEmpty()) c1 else c2
                                    if (finalC.isNotEmpty()) {
                                        conn.setRequestProperty("Cookie", finalC)
                                    }
                                }

                                val status = conn.responseCode
                                val location = conn.getHeaderField("Location")

                                try {
                                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                                    if (stream != null) {
                                        val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                                        val sb = java.lang.StringBuilder()
                                        var line: String? = reader.readLine()
                                        var lineCount = 0
                                        while (line != null && lineCount < 1000) {
                                            sb.append(line).append("\n")
                                            line = reader.readLine()
                                            lineCount++
                                        }
                                        lastBody = sb.toString()
                                        reader.close()
                                    }
                                } catch (_: Exception) {}

                                conn.disconnect()

                                if (status in 300..399 && !location.isNullOrEmpty()) {
                                    val nextUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                                        location
                                    } else {
                                        java.net.URI(currentUrl).resolve(location).toString()
                                    }
                                    currentUrl = nextUrl
                                    redirectCount++
                                    continue
                                }
                                break
                            }

                            activity?.runOnUiThread {
                                val resMap = hashMapOf<String, Any>(
                                    "resolvedUrl" to currentUrl,
                                    "body" to lastBody
                                )
                                result.success(resMap)
                            }
                        } catch (e: Exception) {
                            activity?.runOnUiThread {
                                val resMap = hashMapOf<String, Any>(
                                    "resolvedUrl" to inputUrl,
                                    "body" to ""
                                )
                                result.success(resMap)
                            }
                        }
                    }.start()
                }
                "getCookies" -> {
                    val url = call.argument<String>("url") ?: "https://m.facebook.com"
                    try {
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        val cookies1 = cookieManager.getCookie("https://m.facebook.com") ?: ""
                        val cookies2 = cookieManager.getCookie("https://facebook.com") ?: ""
                        val cookies3 = cookieManager.getCookie("https://www.facebook.com") ?: ""
                        val cookies4 = if (url.isNotEmpty() && url.startsWith("http")) (cookieManager.getCookie(url) ?: "") else ""
                        val allCookies = listOf(cookies1, cookies2, cookies3, cookies4)
                            .filter { it.isNotBlank() }
                            .joinToString("; ")
                        result.success(allCookies)
                    } catch (e: Exception) {
                        result.success("")
                    }
                }
                "setCookies", "setCookie" -> {
                    val cookieStr = call.argument<String>("cookies")
                        ?: call.argument<String>("cookie") ?: ""
                    val clearExisting = call.argument<Boolean>("clearExisting") ?: true
                    try {
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)

                        val applyCookies = {
                            val domains = listOf(
                                "https://m.facebook.com",
                                "https://facebook.com",
                                "https://www.facebook.com"
                            )
                            val items = cookieStr.split(";")
                            for (item in items) {
                                val pair = item.trim()
                                if (pair.isEmpty() || !pair.contains("=")) continue
                                val eqIdx = pair.indexOf("=")
                                val key = pair.substring(0, eqIdx).trim()
                                val value = pair.substring(eqIdx + 1).trim()
                                if (key.isEmpty()) continue

                                val domainPayload = "$key=$value; domain=.facebook.com; path=/; secure; SameSite=None"
                                val simplePayload = "$key=$value; path=/"

                                for (d in domains) {
                                    try {
                                        cookieManager.setCookie(d, domainPayload)
                                        cookieManager.setCookie(d, simplePayload)
                                    } catch (_: Exception) {}
                                }
                            }
                            cookieManager.flush()
                            activity?.runOnUiThread {
                                result.success(true)
                            }
                        }

                        if (clearExisting) {
                            cookieManager.removeAllCookies {
                                cookieManager.flush()
                                applyCookies()
                            }
                        } else {
                            applyCookies()
                        }
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
                "clearCookies" -> {
                    try {
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.removeAllCookies {
                            cookieManager.flush()
                            activity?.runOnUiThread {
                                result.success(true)
                            }
                        }
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = extractIntentData(intent)
        if (data != null) {
            channel?.invokeMethod("onIntent", data)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val next = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                (current + 1).coerceAtMost(max)
            } else {
                (current - 1).coerceAtLeast(0)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleGetInitialIntent(result: MethodChannel.Result) {
        result.success(extractIntentData(intent))
    }

    private fun extractIntentData(intent: Intent?): Map<String, String?>? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action == Intent.ACTION_MAIN) return null

        return when (action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return null
                val uriStr = uri.toString()
                val mimeType = intent.type
                    ?: try { contentResolver.getType(uri) } catch (_: Exception) { null }
                    ?: guessMime(uriStr)
                val displayName = queryDisplayName(uri)
                mapOf("uri" to uriStr, "mimeType" to mimeType, "displayName" to displayName)
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    val uriStr = streamUri.toString()
                    val mimeType = intent.type
                        ?: try { contentResolver.getType(streamUri) } catch (_: Exception) { null }
                        ?: guessMime(uriStr)
                    val displayName = queryDisplayName(streamUri)
                    return mapOf("uri" to uriStr, "mimeType" to mimeType, "displayName" to displayName)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text != null && text.isNotBlank()) {
                    mapOf("uri" to text, "mimeType" to guessMime(text), "displayName" to null)
                } else null
            }
            else -> null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) { null }
    }

    private fun guessMime(uriOrPath: String): String? {
        val lower = uriOrPath.lowercase()
        return when {
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mkv") -> "video/x-matroska"
            else -> null
        }
    }

    private fun handleResolveUriToFilePath(call: MethodCall, result: MethodChannel.Result) {
        val uriStr = call.argument<String>("uri")
        if (uriStr == null) {
            result.success(null)
            return
        }
        try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme != "content") {
                result.success(if (uri.scheme == "file") uri.path else null)
                return
            }
            val cursor = contentResolver.query(uri, arrayOf("_data"), null, null, null)
            val realPath = cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex("_data")
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
            if (realPath != null && File(realPath).exists()) {
                result.success(realPath)
            } else {
                result.success(null)
            }
        } catch (_: Exception) {
            result.success(null)
        }
    }

    private fun handleReadTextUri(call: MethodCall, result: MethodChannel.Result) {
        val uriStr = call.argument<String>("uri")
        if (uriStr == null) {
            result.error("bad_args", "uri required", null)
            return
        }
        try {
            val uri = Uri.parse(uriStr)
            val text = when (uri.scheme) {
                "file" -> {
                    val path = uri.path ?: throw Exception("null path")
                    File(path).readText()
                }
                "content" -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: throw Exception("null stream")
                }
                else -> throw Exception("unsupported scheme: ${uri.scheme}")
            }
            result.success(text)
        } catch (e: Exception) {
            result.error("read_failed", e.message, null)
        }
    }

    private fun mp4ParserMux(videoPath: String, audioPath: String?, outputPath: String): Boolean {
        val videoFile = File(videoPath)
        if (!videoFile.exists() || videoFile.length() == 0L) return false

        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val audioFile = if (!audioPath.isNullOrEmpty()) File(audioPath) else null
        val hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 0L

        if (!hasAudio) {
            // No audio track needed or present. Copy video directly.
            videoFile.copyTo(outFile, overwrite = true)
            return outFile.exists() && outFile.length() > 0L
        }

        try {
            val videoMovie: Movie = MovieCreator.build(videoFile.absolutePath)
            val audioMovie: Movie = MovieCreator.build(audioFile!!.absolutePath)

            val resultMovie = Movie()
            resultMovie.matrix = videoMovie.matrix

            // 1. Add video tracks from videoMovie
            var videoTrackAdded = false
            for (track in videoMovie.tracks) {
                val handler = track.handler?.lowercase() ?: ""
                if (handler == "vide") {
                    resultMovie.addTrack(track)
                    videoTrackAdded = true
                }
            }
            if (!videoTrackAdded) {
                for (track in videoMovie.tracks) {
                    val handler = track.handler?.lowercase() ?: ""
                    if (handler != "soun") {
                        resultMovie.addTrack(track)
                        videoTrackAdded = true
                    }
                }
            }
            if (!videoTrackAdded && videoMovie.tracks.isNotEmpty()) {
                resultMovie.addTrack(videoMovie.tracks[0])
                videoTrackAdded = true
            }

            // 2. Add audio tracks from audioMovie
            var audioTrackAdded = false
            for (track in audioMovie.tracks) {
                val handler = track.handler?.lowercase() ?: ""
                if (handler == "soun" || handler == "audio") {
                    resultMovie.addTrack(track)
                    audioTrackAdded = true
                }
            }
            if (!audioTrackAdded) {
                for (track in audioMovie.tracks) {
                    val handler = track.handler?.lowercase() ?: ""
                    if (handler != "vide") {
                        resultMovie.addTrack(track)
                        audioTrackAdded = true
                    }
                }
            }
            if (!audioTrackAdded && audioMovie.tracks.isNotEmpty()) {
                resultMovie.addTrack(audioMovie.tracks[0])
                audioTrackAdded = true
            }

            val outContainer = DefaultMp4Builder().build(resultMovie)
            FileOutputStream(outFile).use { fos ->
                fos.channel.use { channel ->
                    outContainer.writeContainer(channel)
                }
            }

            if (outFile.exists() && outFile.length() > 0L) {
                return true
            }
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "mp4ParserMux error: ${t.message}", t)
        }

        // Fallback: Copy video directly so the user never loses their download
        return try {
            if (!outFile.exists() || outFile.length() == 0L) {
                videoFile.copyTo(outFile, overwrite = true)
            }
            outFile.exists() && outFile.length() > 0L
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleMux(call: MethodCall, result: MethodChannel.Result) {
        val videoPath = call.argument<String>("videoPath")
        val audioPath = call.argument<String>("audioPath")
        val outputPath = call.argument<String>("outputPath")
        if (videoPath == null || outputPath == null) {
            result.error("bad_args", "videoPath/outputPath required", null)
            return
        }
        Thread {
            try {
                val videoFile = File(videoPath)
                if (!videoFile.exists() || videoFile.length() == 0L) {
                    runOnUiThread { result.error("mux_failed", "video file missing or empty", null) }
                    return@Thread
                }

                val ok = mp4ParserMux(videoPath, audioPath, outputPath)
                val outFile = File(outputPath)

                if (ok && outFile.exists() && outFile.length() > 0L) {
                    runOnUiThread { result.success(true) }
                } else {
                    runOnUiThread { result.error("mux_failed", "could not create output file", null) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "handleMux fatal error: ${t.message}", t)
                try {
                    val vFile = File(videoPath)
                    val oFile = File(outputPath)
                    if (vFile.exists() && vFile.length() > 0L) {
                        vFile.copyTo(oFile, overwrite = true)
                        runOnUiThread { result.success(true) }
                        return@Thread
                    }
                } catch (_: Throwable) {}
                runOnUiThread { result.error("mux_failed", t.message, null) }
            }
        }.start()
    }

    private fun handleThumbnail(call: MethodCall, result: MethodChannel.Result) {
        val videoPath = call.argument<String>("videoPath")
        val outputPath = call.argument<String>("outputPath")
        val timeUs = (call.argument<Number>("timeUs") ?: 1_000_000L).toLong()
        if (videoPath == null || outputPath == null) {
            result.error("bad_args", "videoPath/outputPath required", null)
            return
        }
        val videoFile = File(videoPath)
        if (!videoFile.exists() || videoFile.length() == 0L) {
            result.error("file_missing", "video file missing or empty", null)
            return
        }

        Thread {
            val retriever = MediaMetadataRetriever()
            var fis: java.io.FileInputStream? = null
            try {
                try {
                    retriever.setDataSource(videoFile.absolutePath)
                } catch (_: Exception) {
                    fis = java.io.FileInputStream(videoFile)
                    retriever.setDataSource(fis.fd)
                }

                var frame: Bitmap? = null
                try {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) {}

                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Exception) {}
                }

                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Exception) {}
                }

                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (_: Exception) {}
                }

                if (frame == null) {
                    try {
                        frame = retriever.frameAtTime
                    } catch (_: Exception) {}
                }

                if (frame == null) {
                    runOnUiThread { result.error("thumbnail_failed", "no frame extracted", null) }
                    return@Thread
                }

                val targetWidth = 480
                val scale = targetWidth.toFloat() / frame.width.toFloat()
                val targetHeight = (frame.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)

                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }

                if (scaled !== frame) frame.recycle()
                scaled.recycle()
                runOnUiThread { result.success(true) }
            } catch (t: Throwable) {
                runOnUiThread { result.error("thumbnail_failed", t.message, null) }
            } finally {
                try { retriever.release() } catch (_: Exception) {}
                try { fis?.close() } catch (_: Exception) {}
            }
        }.start()
    }
}
