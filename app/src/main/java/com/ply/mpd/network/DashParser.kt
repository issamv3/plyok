package com.ply.mpd.network

import com.ply.mpd.data.models.DashRepresentation
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object DashParser {

    fun parse(mpdXml: String): List<DashRepresentation> {
        val list = mutableListOf<DashRepresentation>()
        if (mpdXml.isBlank()) return list

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(mpdXml))

            var eventType = parser.eventType
            var currentMimeType = ""
            var currentAdaptationBaseUrl = ""
            var mpdBaseUrl = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (name.lowercase()) {
                            "mpd" -> {
                                // check root attributes if any
                            }
                            "baseurl" -> {
                                val text = parser.nextText() ?: ""
                                if (currentMimeType.isEmpty()) {
                                    mpdBaseUrl = text
                                } else {
                                    currentAdaptationBaseUrl = text
                                }
                            }
                            "adaptationset" -> {
                                currentMimeType = parser.getAttributeValue(null, "mimeType") ?: ""
                                currentAdaptationBaseUrl = ""
                            }
                            "representation" -> {
                                val id = parser.getAttributeValue(null, "id") ?: "rep_${list.size}"
                                val bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                                val width = parser.getAttributeValue(null, "width")?.toIntOrNull()
                                val height = parser.getAttributeValue(null, "height")?.toIntOrNull()
                                val codecs = parser.getAttributeValue(null, "codecs") ?: ""
                                val mime = parser.getAttributeValue(null, "mimeType") ?: currentMimeType
                                val frameRate = parser.getAttributeValue(null, "frameRate") ?: ""
                                val audioSamplingRate = parser.getAttributeValue(null, "audioSamplingRate") ?: ""

                                val isAudio = mime.startsWith("audio") || (height == null && width == null && codecs.contains("mp4a"))
                                val isVideo = mime.startsWith("video") || (height != null && height > 0)

                                // Find inner BaseURL if present
                                var repBaseUrl = ""
                                var innerEvent = parser.next()
                                while (innerEvent != XmlPullParser.END_DOCUMENT) {
                                    if (innerEvent == XmlPullParser.START_TAG && parser.name?.equals("baseurl", ignoreCase = true) == true) {
                                        repBaseUrl = parser.nextText() ?: ""
                                        break
                                    } else if (innerEvent == XmlPullParser.END_TAG && parser.name?.equals("representation", ignoreCase = true) == true) {
                                        break
                                    }
                                    innerEvent = parser.next()
                                }

                                val finalBaseUrl = when {
                                    repBaseUrl.isNotBlank() -> repBaseUrl
                                    currentAdaptationBaseUrl.isNotBlank() -> currentAdaptationBaseUrl
                                    else -> mpdBaseUrl
                                }

                                val label = if (isVideo && height != null) {
                                    "${height}p"
                                } else if (isAudio) {
                                    "Audio (${bandwidth / 1000}k)"
                                } else {
                                    "Track ${id}"
                                }

                                list.add(
                                    DashRepresentation(
                                        id = id,
                                        mimeType = mime,
                                        codecs = codecs,
                                        bandwidth = bandwidth,
                                        width = width,
                                        height = height,
                                        frameRate = frameRate,
                                        audioSamplingRate = audioSamplingRate,
                                        baseUrl = finalBaseUrl,
                                        isAudio = isAudio,
                                        isVideo = isVideo,
                                        label = label
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Fallback: regex search if XML is broken or contains multiple root nodes
            return parseWithRegexFallback(mpdXml)
        }

        // Return sorted: highest resolution video first, then audio
        return list.sortedWith(
            compareByDescending<DashRepresentation> { it.isVideo }
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.bandwidth }
        )
    }

    private fun parseWithRegexFallback(xml: String): List<DashRepresentation> {
        val list = mutableListOf<DashRepresentation>()
        val repRegex = Regex("<Representation[^>]*id=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
        val heightRegex = Regex("height=\"(\\d+)\"")
        val widthRegex = Regex("width=\"(\\d+)\"")
        val bwRegex = Regex("bandwidth=\"(\\d+)\"")
        val mimeRegex = Regex("mimeType=\"([^\"]+)\"")
        val codecsRegex = Regex("codecs=\"([^\"]+)\"")

        val matches = repRegex.findAll(xml)
        for (m in matches) {
            val tag = m.value
            val id = m.groupValues[1]
            val height = heightRegex.find(tag)?.groupValues?.get(1)?.toIntOrNull()
            val width = widthRegex.find(tag)?.groupValues?.get(1)?.toIntOrNull()
            val bw = bwRegex.find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val mime = mimeRegex.find(tag)?.groupValues?.get(1) ?: ""
            val codecs = codecsRegex.find(tag)?.groupValues?.get(1) ?: ""

            val isVideo = (height != null && height > 0) || mime.contains("video")
            val isAudio = mime.contains("audio") || (!isVideo && codecs.contains("mp4a"))

            list.add(
                DashRepresentation(
                    id = id,
                    mimeType = mime,
                    codecs = codecs,
                    bandwidth = bw,
                    width = width,
                    height = height,
                    baseUrl = "",
                    isVideo = isVideo,
                    isAudio = isAudio,
                    label = if (height != null) "${height}p" else "Stream $id"
                )
            )
        }
        return list
    }
}
