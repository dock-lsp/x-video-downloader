package com.xvideo.downloader.data.remote.api

import com.xvideo.downloader.data.model.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Twitter/X Video API Service
 *
 * Flow: Extract ID → Skip auth via Syndication API → Parse JSON →
 *       Get m3u8 playlist → Parse m3u8 → Separate audio/video streams
 *
 * The syndication API (syndication.twitter.com) provides public tweet data
 * without requiring authentication tokens.
 */
class TwitterApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        // Syndication API - public, no auth required
        private const val SYNDICATION_API = "https://syndication.twitter.com/srv/timeline-profile/screen-name/{username}"
        private const val SYNDICATION_TWEET = "https://cdn.syndication.twimg.com/tweet-result?id={tweetId}&lang=en"

        // VxTwitter fallback - public proxy API
        private const val VXTWITTER_API = "https://api.vxtwitter.com/{tweetId}"

        // FxTwitter fallback
        private const val FXTWITTER_API = "https://api.fxtwitter.com/{tweetId}"

        @Volatile
        private var instance: TwitterApiService? = null

        fun getInstance(): TwitterApiService {
            return instance ?: synchronized(this) {
                instance ?: TwitterApiService().also { instance = it }
            }
        }
    }

    suspend fun parseTweetUrl(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val tweetId = extractTweetId(url)
                ?: return@withContext Result.failure(Exception("无效的 Twitter/X 链接格式"))

            // Strategy 1: Try FxTwitter API (most reliable for m3u8)
            val fxResult = tryFxTwitterApi(tweetId, url)
            if (fxResult != null) return@withContext Result.success(fxResult)

            // Strategy 2: Try VxTwitter API (direct MP4 fallback)
            val vxResult = tryVxTwitterApi(tweetId, url)
            if (vxResult != null) return@withContext Result.success(vxResult)

            // Strategy 3: Try Syndication API (no auth needed)
            val syndResult = trySyndicationApi(tweetId, url)
            if (syndResult != null) return@withContext Result.success(syndResult)

            // Strategy 4: Try direct webpage scraping
            val webResult = tryWebpageScraping(tweetId, url)
            if (webResult != null) return@withContext Result.success(webResult)

            Result.failure(Exception("无法解析该推文的视频，请检查链接是否正确"))
        } catch (e: Exception) {
            Result.failure(Exception("解析失败: ${e.message}"))
        }
    }

    // ==================== Strategy 1: FxTwitter API ====================
    private suspend fun tryFxTwitterApi(tweetId: String, url: String): VideoInfo? {
        return try {
            val apiUrl = FXTWITTER_API.replace("{tweetId}", tweetId)
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject

            // FxTwitter wraps response in "tweet" key
            val tweet = json.getAsJsonObject("tweet") ?: json

            parseFxTwitterResponse(tweet, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFxTwitterResponse(tweet: com.google.gson.JsonObject, url: String): VideoInfo? {
        try {
            val tweetId = tweet.get("id")?.asString ?: tweet.get("tweet_id")?.asString ?: return null
            val text = tweet.get("text")?.asString ?: ""
            val author = tweet.getAsJsonObject("author")
            val authorName = author?.get("name")?.asString ?: "Unknown"
            val authorUsername = author?.get("screen_name")?.asString
                ?: author?.get("username")?.asString ?: "unknown"

            val videoVariants = mutableListOf<VideoVariant>()
            val gifVariants = mutableListOf<GifVariant>()
            var thumbnailUrl: String? = null
            var m3u8Url: String? = null

            // Parse media
            val media = tweet.getAsJsonObject("media")
            if (media != null) {
                // Check for m3u8 (HLS) - this is what we want for the new flow
                val m3u8 = media.get("m3u8")?.asString
                    ?: media.getAsJsonObject("video")?.get("m3u8_url")?.asString
                if (m3u8 != null) {
                    m3u8Url = m3u8
                }

                // Parse video variants
                val videos = media.getAsJsonArray("videos")
                if (videos != null) {
                    for (video in videos) {
                        val videoObj = video.asJsonObject
                        thumbnailUrl = videoObj.get("thumbnail_url")?.asString ?: thumbnailUrl

                        val m3u8Link = videoObj.get("m3u8_url")?.asString
                            ?: videoObj.get("url")?.asString
                        if (m3u8Link != null && m3u8Link.contains("m3u8")) {
                            m3u8Url = m3u8Link
                        }

                        // Direct MP4 variants
                        val variants = videoObj.getAsJsonArray("variants")
                        if (variants != null) {
                            for (variant in variants) {
                                val vObj = variant.asJsonObject
                                val vUrl = vObj.get("url")?.asString ?: continue
                                val bitrate = vObj.get("bitrate")?.asInt ?: 0
                                val contentType = vObj.get("content_type")?.asString ?: "video/mp4"
                                if (contentType == "video/mp4") {
                                    videoVariants.add(VideoVariant(vUrl, bitrate, contentType))
                                }
                            }
                        }
                    }
                }

                // Check for photos array (some responses use this)
                val photos = media.getAsJsonArray("photos")
                if (photos != null && videoVariants.isEmpty()) {
                    // Not a video tweet
                }
            }

            // If we have m3u8 but no direct variants, that's fine - we'll use m3u8 flow
            if (videoVariants.isEmpty() && m3u8Url == null) return null

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = authorName,
                authorUsername = authorUsername,
                tweetText = text,
                thumbnailUrl = thumbnailUrl,
                videoVariants = videoVariants,
                gifVariants = gifVariants,
                m3u8Url = m3u8Url,
                hasM3u8 = m3u8Url != null
            )
        } catch (e: Exception) {
            return null
        }
    }

    // ==================== Strategy 2: VxTwitter API ====================
    private suspend fun tryVxTwitterApi(tweetId: String, url: String): VideoInfo? {
        return try {
            val apiUrl = VXTWITTER_API.replace("{tweetId}", tweetId)
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            parseVxTwitterResponse(body, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVxTwitterResponse(json: String, url: String): VideoInfo? {
        try {
            val parser = JsonParser.parseString(json).asJsonObject
            val tweetId = parser.get("tweet_id")?.asString ?: parser.get("id")?.asString ?: return null
            val text = parser.get("text")?.asString ?: ""
            val user = parser.getAsJsonObject("user")
            val authorName = user?.get("name")?.asString ?: "Unknown"
            val authorUsername = user?.get("screen_name")?.asString ?: "unknown"

            val videoVariants = mutableListOf<VideoVariant>()
            val gifVariants = mutableListOf<GifVariant>()
            var thumbnailUrl: String? = null
            var m3u8Url: String? = null

            val mediaArray = parser.getAsJsonArray("media")
            if (mediaArray != null) {
                for (media in mediaArray) {
                    val mediaObj = media.asJsonObject
                    val type = mediaObj.get("type")?.asString

                    if (type == "video") {
                        thumbnailUrl = mediaObj.get("media_url_https")?.asString
                            ?: mediaObj.get("thumbnail_url")?.asString

                        // Check for m3u8 URL
                        val m3u8 = mediaObj.get("m3u8_url")?.asString
                            ?: mediaObj.get("url")?.asString?.takeIf { it.contains("m3u8") }
                        if (m3u8 != null) {
                            m3u8Url = m3u8
                        }

                        val videoInfo = mediaObj.getAsJsonObject("video_info")
                        if (videoInfo != null) {
                            val variants = videoInfo.getAsJsonArray("variants")
                            if (variants != null) {
                                for (variant in variants) {
                                    val variantObj = variant.asJsonObject
                                    val vUrl = variantObj.get("url")?.asString ?: continue
                                    val bitrate = variantObj.get("bitrate")?.asInt ?: 0
                                    val contentType = variantObj.get("content_type")?.asString ?: "video/mp4"

                                    if (contentType == "video/mp4") {
                                        videoVariants.add(VideoVariant(vUrl, bitrate, contentType))
                                    } else if (vUrl.contains("m3u8") && m3u8Url == null) {
                                        m3u8Url = vUrl
                                    }
                                }
                            }
                        }
                    } else if (type == "animated_gif") {
                        val videoInfo = mediaObj.getAsJsonObject("video_info")
                        if (videoInfo != null) {
                            val variants = videoInfo.getAsJsonArray("variants")
                            if (variants != null) {
                                for (variant in variants) {
                                    val variantObj = variant.asJsonObject
                                    val vUrl = variantObj.get("url")?.asString ?: continue
                                    val bitrate = variantObj.get("bitrate")?.asInt ?: 0
                                    gifVariants.add(GifVariant(vUrl, bitrate))
                                }
                            }
                        }
                    }
                }
            }

            if (videoVariants.isEmpty() && gifVariants.isEmpty() && m3u8Url == null) return null

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = authorName,
                authorUsername = authorUsername,
                tweetText = text,
                thumbnailUrl = thumbnailUrl,
                videoVariants = videoVariants,
                gifVariants = gifVariants,
                m3u8Url = m3u8Url,
                hasM3u8 = m3u8Url != null
            )
        } catch (e: Exception) {
            return null
        }
    }

    // ==================== Strategy 3: Syndication API (No Auth) ====================
    private suspend fun trySyndicationApi(tweetId: String, url: String): VideoInfo? {
        return try {
            val apiUrl = SYNDICATION_TWEET.replace("{tweetId}", tweetId)
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("Referer", "https://platform.twitter.com/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            parseSyndicationResponse(body, tweetId, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSyndicationResponse(json: String, tweetId: String, url: String): VideoInfo? {
        try {
            val parser = JsonParser.parseString(json).asJsonObject
            val text = parser.get("text")?.asString ?: ""

            val author = parser.getAsJsonObject("author")
            val authorName = author?.get("name")?.asString ?: "Unknown"
            val authorUsername = author?.get("screen_name")?.asString ?: "unknown"

            val videoVariants = mutableListOf<VideoVariant>()
            var thumbnailUrl: String? = null
            var m3u8Url: String? = null

            // Syndication API puts video in "video" key
            val video = parser.getAsJsonObject("video")
            if (video != null) {
                thumbnailUrl = video.get("thumbnail_url")?.asString
                m3u8Url = video.get("m3u8_url")?.asString

                val variants = video.getAsJsonArray("variants")
                if (variants != null) {
                    for (variant in variants) {
                        val vObj = variant.asJsonObject
                        val vUrl = vObj.get("src")?.asString ?: vObj.get("url")?.asString ?: continue
                        val bitrate = vObj.get("bitrate")?.asInt ?: 0
                        val contentType = vObj.get("content_type")?.asString ?: "video/mp4"

                        if (contentType == "video/mp4") {
                            videoVariants.add(VideoVariant(vUrl, bitrate, contentType))
                        } else if (vUrl.contains("m3u8") && m3u8Url == null) {
                            m3u8Url = vUrl
                        }
                    }
                }
            }

            // Check photos array for video
            val photos = parser.getAsJsonArray("photos")
            if (photos != null && video == null) {
                for (photo in photos) {
                    val photoObj = photo.asJsonObject
                    val videoUrl = photoObj.get("video_url")?.asString
                    if (videoUrl != null) {
                        videoVariants.add(VideoVariant(videoUrl, 0, "video/mp4"))
                    }
                }
            }

            if (videoVariants.isEmpty() && m3u8Url == null) return null

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = authorName,
                authorUsername = authorUsername,
                tweetText = text,
                thumbnailUrl = thumbnailUrl,
                videoVariants = videoVariants,
                gifVariants = emptyList(),
                m3u8Url = m3u8Url,
                hasM3u8 = m3u8Url != null
            )
        } catch (e: Exception) {
            return null
        }
    }

    // ==================== Strategy 4: Webpage Scraping ====================
    private suspend fun tryWebpageScraping(tweetId: String, url: String): VideoInfo? {
        return try {
            val request = Request.Builder()
                .url("https://x.com/i/status/$tweetId")
                .addHeader("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            parseWebpageResponse(body, tweetId, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseWebpageResponse(html: String, tweetId: String, url: String): VideoInfo? {
        try {
            val videoVariants = mutableListOf<VideoVariant>()
            var m3u8Url: String? = null

            // Extract m3u8 URL from page
            val m3u8Pattern = Pattern.compile("https://video\\.twimg\\.com/[^\"\\s]+\\.m3u8[^\"\\s]*")
            val m3u8Matcher = m3u8Pattern.matcher(html)
            if (m3u8Matcher.find()) {
                m3u8Url = m3u8Matcher.group()
            }

            // Extract direct MP4 URLs
            val videoPattern = Pattern.compile("https://video\\.twimg\\.com/[^\"\\s]+\\.mp4[^\"\\s]*")
            val videoMatcher = videoPattern.matcher(html)
            while (videoMatcher.find()) {
                val videoUrl = videoMatcher.group()
                val bitrate = extractBitrateFromUrl(videoUrl)
                videoVariants.add(VideoVariant(videoUrl, bitrate, "video/mp4"))
            }

            if (videoVariants.isEmpty() && m3u8Url == null) return null

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = "Twitter User",
                authorUsername = "unknown",
                tweetText = "",
                thumbnailUrl = null,
                videoVariants = videoVariants,
                gifVariants = emptyList(),
                m3u8Url = m3u8Url,
                hasM3u8 = m3u8Url != null
            )
        } catch (e: Exception) {
            return null
        }
    }

    // ==================== M3U8 Parsing ====================

    /**
     * Parse an m3u8 playlist URL to extract separate audio and video stream URLs.
     * Returns a list of M3u8Stream objects sorted by quality (highest first).
     *
     * Twitter/X uses HLS with separate audio and video renditions.
     * The master playlist contains:
     * - #EXT-X-MEDIA:TYPE=AUDIO → audio stream URL
     * - #EXT-X-STREAM-INF → video stream URLs with bandwidth/resolution
     */
    suspend fun parseM3u8Playlist(m3u8Url: String): Result<List<M3u8Stream>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(m3u8Url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch m3u8: ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty m3u8 response"))

            val streams = parseM3u8Content(body, m3u8Url)
            if (streams.isEmpty()) {
                return@withContext Result.failure(Exception("No streams found in m3u8"))
            }

            Result.success(streams.sortedByDescending { it.bandwidth })
        } catch (e: Exception) {
            Result.failure(Exception("M3U8 parse error: ${e.message}"))
        }
    }

    private fun parseM3u8Content(content: String, baseUrl: String): List<M3u8Stream> {
        val streams = mutableListOf<M3u8Stream>()
        val lines = content.lines()

        var audioUrl: String? = null
        var currentBandwidth = 0L
        var currentResolution: String? = null

        // Resolve relative URLs
        val basePrefix = baseUrl.substringBeforeLast("/")

        for (i in lines.indices) {
            val line = lines[i].trim()

            // Extract audio rendition URL
            if (line.startsWith("#EXT-X-MEDIA") && line.contains("AUDIO")) {
                val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                if (uriMatch != null) {
                    audioUrl = resolveUrl(uriMatch.groupValues[1], basePrefix)
                }
            }

            // Extract video stream info
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                currentBandwidth = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0

                val resolutionMatch = Regex("RESOLUTION=(\\d+x\\d+)").find(line)
                currentResolution = resolutionMatch?.groupValues?.get(1)

                // Next line should be the URL
                if (i + 1 < lines.size) {
                    val urlLine = lines[i + 1].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                        val videoUrl = resolveUrl(urlLine, basePrefix)
                        val quality = resolutionToQuality(currentResolution, currentBandwidth)
                        streams.add(M3u8Stream(
                            videoUrl = videoUrl,
                            audioUrl = audioUrl,
                            bandwidth = currentBandwidth,
                            resolution = currentResolution,
                            quality = quality
                        ))
                    }
                }
            }
        }

        // If no streams found but content itself is a media playlist (has segments),
        // treat the URL as a single stream
        if (streams.isEmpty() && content.contains("#EXTINF")) {
            streams.add(M3u8Stream(
                videoUrl = baseUrl,
                audioUrl = null,
                bandwidth = 0,
                resolution = null,
                quality = "Unknown"
            ))
        }

        return streams
    }

    private fun resolveUrl(url: String, basePrefix: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$basePrefix/$url"
        }
    }

    private fun resolutionToQuality(resolution: String?, bandwidth: Long): String {
        if (resolution != null) {
            val height = resolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0
            return when {
                height >= 2160 -> "4K"
                height >= 1440 -> "2K"
                height >= 720 -> "HD (${height}p)"
                height >= 480 -> "SD (${height}p)"
                height > 0 -> "${height}p"
                else -> "Unknown"
            }
        }
        return when {
            bandwidth >= 8000000 -> "4K"
            bandwidth >= 4000000 -> "2K"
            bandwidth >= 2000000 -> "HD"
            else -> "SD"
        }
    }

    // ==================== Helpers ====================

    private fun extractTweetId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("twitter\\.com/\\w+/status/(\\d+)"),
            Pattern.compile("x\\.com/\\w+/status/(\\d+)"),
            Pattern.compile("twitter\\.com/\\w+/status/(\\w+)"),
            Pattern.compile("x\\.com/\\w+/status/(\\w+)")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun extractBitrateFromUrl(url: String): Int {
        val bitratePattern = Pattern.compile("bitrate=(\\d+)")
        val matcher = bitratePattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull() ?: 0
        } else {
            0
        }
    }
}
