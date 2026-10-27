package com.ply.mpd.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ExtractedVideo(
    val title: String,
    val mpdContent: String? = null,
    val directStreamUrl: String? = null,
    val fbVideoId: String? = null,
    val originalUrl: String? = null,
    val isFacebook: Boolean = false
)

object FacebookExtractor {
    private const val TAG = "FacebookExtractor"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /**
     * Extracts video ID from various Facebook URL forms
     */
    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:facebook\\.com/(?:[^/]+/)?(?:videos|reel|watch)/?|watch/\\?v=|v=)(\\d{10,20})"),
            Pattern.compile("facebook\\.com/share/[vr]/(\\d{10,20})"),
            Pattern.compile("fb\\.watch/([a-zA-Z0-9_-]+)"),
            Pattern.compile("\\b(\\d{12,20})\\b")
        )
        for (p in patterns) {
            val matcher = p.matcher(url)
            if (matcher.find()) {
                val group = matcher.group(1)
                if (group != null && group.isNotBlank()) return group
            }
        }
        return null
    }

    /**
     * Checks if a URL is expired based on the Facebook CDN `oe` parameter
     */
    fun isUrlExpired(url: String): Boolean {
        if (!url.contains("fbcdn.net") && !url.contains("facebook.com")) return false
        val oeRegex = Regex("[?&]oe=([0-9a-fA-F]+)")
        val match = oeRegex.find(url) ?: return false
        val hexValue = match.groupValues[1]
        val expireEpochSeconds = hexValue.toLongOrNull(16) ?: return false
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        // Consider expired if within 60 seconds of expiration
        return nowEpochSeconds >= (expireEpochSeconds - 60)
    }

    suspend fun resolveAndExtract(inputUrl: String): ExtractedVideo = withContext(Dispatchers.IO) {
        val trimmed = inputUrl.trim()
        val isFb = trimmed.contains("facebook.com") || trimmed.contains("fb.watch") || trimmed.contains("fb.com") || trimmed.contains("fbcdn.net")

        // If it's a direct MPD URL and not FB webpage
        if (!isFb && (trimmed.endsWith(".mpd") || trimmed.contains(".mpd?"))) {
            return@withContext fetchDirectMpd(trimmed)
        }

        var targetUrl = trimmed
        // If it's fb.watch or share link, follow redirect
        if (targetUrl.contains("fb.watch") || targetUrl.contains("/share/")) {
            val resolved = followRedirect(targetUrl)
            if (resolved != null) {
                targetUrl = resolved
            }
        }

        val videoId = extractVideoId(targetUrl) ?: extractVideoId(trimmed)

        // Try extracting using mobile web page request
        val extracted = extractFromWebpage(targetUrl, videoId)
        if (extracted != null) {
            return@withContext extracted.copy(
                originalUrl = trimmed,
                fbVideoId = videoId,
                isFacebook = true
            )
        }

        // Fallback: try GraphQL if video ID is found
        if (videoId != null) {
            val gqlResult = extractViaGraphql(videoId)
            if (gqlResult != null) {
                return@withContext gqlResult.copy(
                    originalUrl = trimmed,
                    fbVideoId = videoId,
                    isFacebook = true
                )
            }
        }

        // Direct fallback: if input is a valid URL, return it as direct stream
        ExtractedVideo(
            title = "Video ${System.currentTimeMillis() % 10000}",
            directStreamUrl = trimmed,
            originalUrl = trimmed,
            fbVideoId = videoId,
            isFacebook = isFb
        )
    }

    private fun followRedirect(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .head()
                .build()
            client.newCall(req).execute().use { response ->
                response.request.url.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchDirectMpd(mpdUrl: String): ExtractedVideo {
        return try {
            val req = Request.Builder()
                .url(mpdUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { response ->
                val body = response.body?.string() ?: ""
                val title = mpdUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "DASH Stream" }
                ExtractedVideo(
                    title = title,
                    mpdContent = body,
                    directStreamUrl = mpdUrl,
                    isFacebook = false
                )
            }
        } catch (e: Exception) {
            ExtractedVideo(
                title = "DASH Stream",
                directStreamUrl = mpdUrl,
                isFacebook = false
            )
        }
    }

    private fun extractFromWebpage(url: String, videoId: String?): ExtractedVideo? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: return null
                parseFacebookHtml(html, videoId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractFromWebpage error: ${e.message}")
            null
        }
    }

    private fun parseFacebookHtml(html: String, videoId: String?): ExtractedVideo? {
        // Look for dash_manifest
        val dashRegex = Regex("\"dash_manifest\"\\s*:\\s*\"([^\"]+)\"")
        val dashMatch = dashRegex.find(html)
        var mpd: String? = null
        if (dashMatch != null) {
            val raw = dashMatch.groupValues[1]
            mpd = unescapeString(raw)
        }

        // Look for direct playable URLs
        var streamUrl: String? = null
        val directPatterns = listOf(
            Regex("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"hd_src\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"sd_src\"\\s*:\\s*\"([^\"]+)\"")
        )
        for (pattern in directPatterns) {
            val m = pattern.find(html)
            if (m != null) {
                streamUrl = unescapeString(m.groupValues[1])
                break
            }
        }

        // Title extraction
        val titleRegex = Regex("<title>([^<]+)</title>")
        var title = titleRegex.find(html)?.groupValues?.get(1)?.trim() ?: "Facebook Video"
        if (title.contains("| Facebook")) {
            title = title.substringBefore("| Facebook").trim()
        }

        if (mpd != null || streamUrl != null) {
            return ExtractedVideo(
                title = title.ifEmpty { "Facebook Video ${videoId ?: ""}" },
                mpdContent = mpd,
                directStreamUrl = streamUrl,
                fbVideoId = videoId,
                isFacebook = true
            )
        }
        return null
    }

    private fun extractViaGraphql(videoId: String): ExtractedVideo? {
        return try {
            val body = FormBody.Builder()
                .add("doc_id", "27929861826665845")
                .add("variables", "{\"video_id\":\"$videoId\"}")
                .build()

            val req = Request.Builder()
                .url("https://www.facebook.com/api/graphql/")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val json = resp.body?.string() ?: return null
                parseFacebookHtml(json, videoId)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun unescapeString(input: String): String {
        var str = input
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return str
    }
}
