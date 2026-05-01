package com.xvideo.downloader.data.remote.api

import com.xvideo.downloader.data.model.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Twitter/X Video API Service
 *
 * Core Flow (same as x-twitter-downloader.com):
 * 1. Extract tweet ID from URL
 * 2. Get Guest Token (bypass auth)
 * 3. Call Twitter GraphQL API to get tweet detail JSON
 * 4. Parse JSON → extract video variants (mp4) and m3u8 URLs
 * 5. Return direct download URLs
 *
 * Uses Twitter's internal GraphQL API with public Bearer token + Guest Token.
 * This is the same approach used by all major Twitter video download services.
 */
class TwitterApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        // Twitter's public Bearer token (embedded in their JS, publicly known)
        private const val BEARER_TOKEN = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

        // Guest token activation endpoint
        private const val GUEST_ACTIVATE_URL = "https://api.twitter.com/1.1/guest/activate.json"

        // GraphQL endpoint for tweet detail
        private const val GRAPHQL_URL = "https://twitter.com/i/api/graphql/H8OOoI-5ZE4NxgRr8lfyWg/TweetResultByRestId"

        // GraphQL features (required parameter)
        private const val GRAPHQL_FEATURES = """{"creator_subscriptions_tweet_preview_api_enabled":true,"tweetypie_unmention_optimization_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":false,"tweet_awards_web_tipping_enabled":false,"creator_subscriptions_quote_tweet_preview_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"rweb_video_timestamps_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_timeline_navigation_enabled":true}"""

        // Primary: x-twitter-downloader.com API (user preferred)
        private const val XT_DOWNLOADER_API = "https://x-twitter-downloader.com/api/download"

        // Fallback: VxTwitter API (third-party proxy, no auth needed)
        private const val VXTWITTER_API = "https://api.vxtwitter.com"

        // Fallback: FxTwitter API
        private const val FXTWITTER_API = "https://api.fxtwitter.com"

        @Volatile
        private var instance: TwitterApiService? = null

        // Cache guest token for reuse
        @Volatile
        private var cachedGuestToken: String? = null
        private var guestTokenExpiry: Long = 0

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

            // Strategy 1: x-twitter-downloader.com (primary, user preferred)
            val xtResult = tryXTwitterDownloaderApi(url, tweetId)
            if (xtResult != null) return@withContext Result.success(xtResult)

            // Strategy 2: Twitter GraphQL API (most reliable, same as website)
            val gqlResult = tryGraphQLApi(tweetId, url)
            if (gqlResult != null) return@withContext Result.success(gqlResult)

            // Strategy 3: FxTwitter proxy
            val fxResult = tryFxTwitterApi(tweetId, url)
            if (fxResult != null) return@withContext Result.success(fxResult)

            // Strategy 4: VxTwitter proxy
            val vxResult = tryVxTwitterApi(tweetId, url)
            if (vxResult != null) return@withContext Result.success(vxResult)

            Result.failure(Exception("无法解析该推文的视频。请确认链接正确且推文包含视频"))
        } catch (e: Exception) {
            Result.failure(Exception("解析失败: ${e.message}"))
        }
    }

    // ==================== Strategy 1: x-twitter-downloader.com ====================

    private suspend fun tryXTwitterDownloaderApi(url: String, tweetId: String): VideoInfo? {
        return try {
            val apiUrl = "$XT_DOWNLOADER_API?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("Referer", "https://x-twitter-downloader.com/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            parseXTwitterDownloaderResponse(body, tweetId, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseXTwitterDownloaderResponse(jsonStr: String, tweetId: String, url: String): VideoInfo? {
        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject

            // The API returns video info with download links
            // Try to extract from various response formats
            val videoVariants = mutableListOf<VideoVariant>()
            var thumbnailUrl: String? = null
            var authorName = "Unknown"
            var authorUsername = "unknown"
            var tweetText = ""
            var m3u8Url: String? = null

            // Extract author info if available
            if (root.has("author")) {
                val author = root.getAsJsonObject("author")
                authorName = author?.get("name")?.asString ?: authorName
                authorUsername = author?.get("username")?.asString
                    ?: author?.get("screen_name")?.asString ?: authorUsername
            }
            if (root.has("user")) {
                val user = root.getAsJsonObject("user")
                authorName = user?.get("name")?.asString ?: authorName
                authorUsername = user?.get("screen_name")?.asString
                    ?: user?.get("username")?.asString ?: authorUsername
            }
            tweetText = root.get("text")?.asString ?: root.get("description")?.asString ?: ""
            thumbnailUrl = root.get("thumbnail")?.asString
                ?: root.get("thumbnail_url")?.asString
                ?: root.get("image")?.asString

            // Parse video links from various possible formats
            // Format 1: { "video": { "url": "...", "quality": "..." } }
            if (root.has("video")) {
                val video = root.getAsJsonObject("video")
                if (video != null) {
                    val vUrl = video.get("url")?.asString
                    if (vUrl != null) {
                        val quality = video.get("quality")?.asString ?: "HD"
                        val bitrate = qualityToBitrate(quality)
                        videoVariants.add(VideoVariant(vUrl, bitrate, "video/mp4"))
                    }
                }
            }

            // Format 2: { "videos": [{ "url": "...", "quality": "...", "bitrate": ... }] }
            if (root.has("videos")) {
                val videos = root.getAsJsonArray("videos")
                if (videos != null) {
                    for (v in videos) {
                        val vObj = v.asJsonObject
                        val vUrl = vObj.get("url")?.asString ?: continue
                        val bitrate = vObj.get("bitrate")?.asInt
                            ?: qualityToBitrate(vObj.get("quality")?.asString ?: "HD")
                        val contentType = vObj.get("content_type")?.asString ?: "video/mp4"
                        videoVariants.add(VideoVariant(vUrl, bitrate, contentType))
                    }
                }
            }

            // Format 3: { "download": { "url": "..." } } or { "download_url": "..." }
            if (videoVariants.isEmpty()) {
                if (root.has("download")) {
                    val download = root.getAsJsonObject("download")
                    val dUrl = download?.get("url")?.asString
                    if (dUrl != null) {
                        videoVariants.add(VideoVariant(dUrl, 2000000, "video/mp4"))
                    }
                }
                root.get("download_url")?.asString?.let {
                    videoVariants.add(VideoVariant(it, 2000000, "video/mp4"))
                }
            }

            // Format 4: { "formats": [{ "url": "...", "quality": "...", "ext": "mp4" }] }
            if (videoVariants.isEmpty() && root.has("formats")) {
                val formats = root.getAsJsonArray("formats")
                if (formats != null) {
                    for (f in formats) {
                        val fObj = f.asJsonObject
                        val fUrl = fObj.get("url")?.asString ?: continue
                        val ext = fObj.get("ext")?.asString ?: "mp4"
                        if (ext == "mp4" || fUrl.contains(".mp4")) {
                            val bitrate = fObj.get("bitrate")?.asInt
                                ?: qualityToBitrate(fObj.get("quality")?.asString ?: "HD")
                            videoVariants.add(VideoVariant(fUrl, bitrate, "video/mp4"))
                        } else if (fUrl.contains(".m3u8")) {
                            m3u8Url = fUrl
                        }
                    }
                }
            }

            // Format 5: Direct array of links
            if (videoVariants.isEmpty() && root.has("links")) {
                val links = root.getAsJsonArray("links")
                if (links != null) {
                    for (l in links) {
                        val lUrl = l.asString ?: continue
                        if (lUrl.contains(".mp4") || lUrl.contains("video")) {
                            videoVariants.add(VideoVariant(lUrl, 2000000, "video/mp4"))
                        } else if (lUrl.contains(".m3u8")) {
                            m3u8Url = lUrl
                        }
                    }
                }
            }

            if (videoVariants.isEmpty() && m3u8Url == null) return null

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = authorName,
                authorUsername = authorUsername,
                tweetText = tweetText,
                thumbnailUrl = thumbnailUrl,
                videoVariants = videoVariants.distinctBy { it.url },
                gifVariants = emptyList(),
                m3u8Url = m3u8Url,
                hasM3u8 = m3u8Url != null
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun qualityToBitrate(quality: String): Int {
        return when (quality.lowercase()) {
            "4k", "2160p" -> 8000000
            "2k", "1440p" -> 5000000
            "1080p", "hd", "high" -> 2500000
            "720p", "medium" -> 1500000
            "480p", "sd", "low" -> 800000
            "360p" -> 500000
            else -> 2000000
        }
    }

    // ==================== Strategy 2: Twitter GraphQL API ====================
    // (Direct Twitter API - same approach as x-twitter-downloader.com uses internally)

    private suspend fun tryGraphQLApi(tweetId: String, url: String): VideoInfo? {
        return try {
            val guestToken = getGuestToken() ?: return null

            val variables = """{"tweetId":"$tweetId","withCommunity":false,"includePromutedContent":false,"withVoice":false}"""
            val encodedVariables = java.net.URLEncoder.encode(variables, "UTF-8")
            val encodedFeatures = java.net.URLEncoder.encode(GRAPHQL_FEATURES, "UTF-8")

            val apiUrl = "$GRAPHQL_URL?variables=$encodedVariables&features=$encodedFeatures"

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $BEARER_TOKEN")
                .addHeader("x-guest-token", guestToken)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("x-twitter-active-user", "yes")
                .addHeader("x-twitter-client-language", "en")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return null
            }

            val body = response.body?.string() ?: return null
            parseGraphQLResponse(body, tweetId, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun getGuestToken(): String? {
        // Return cached token if still valid
        val cached = cachedGuestToken
        if (cached != null && System.currentTimeMillis() < guestTokenExpiry) {
            return cached
        }

        return try {
            val request = Request.Builder()
                .url(GUEST_ACTIVATE_URL)
                .post("".toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $BEARER_TOKEN")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            val token = json.get("guest_token")?.asString ?: return null

            // Cache for 30 minutes
            cachedGuestToken = token
            guestTokenExpiry = System.currentTimeMillis() + 30 * 60 * 1000

            token
        } catch (e: Exception) {
            null
        }
    }

    private fun parseGraphQLResponse(jsonStr: String, tweetId: String, url: String): VideoInfo? {
        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject

            // Navigate: data -> tweetResult -> result
            val data = root.getAsJsonObject("data") ?: return null
            val tweetResult = data.getAsJsonObject("tweetResult") ?: return null
            val result = tweetResult.getAsJsonObject("result") ?: return null

            // Handle tweet types (Tweet vs TweetWithVisibilityResults)
            val tweet = if (result.has("tweet")) {
                result.getAsJsonObject("tweet")
            } else {
                result
            } ?: return null

            val legacy = tweet.getAsJsonObject("legacy") ?: return null
            val core = tweet.getAsJsonObject("core")

            // Extract author info
            val userResults = core?.getAsJsonObject("user_results")
                ?.getAsJsonObject("result")
                ?.getAsJsonObject("legacy")
            val authorName = userResults?.get("name")?.asString ?: "Unknown"
            val authorUsername = userResults?.get("screen_name")?.asString ?: "unknown"

            val text = legacy.get("full_text")?.asString ?: ""

            // Extract video info from extended_entities
            val videoVariants = mutableListOf<VideoVariant>()
            var thumbnailUrl: String? = null
            var m3u8Url: String? = null

            val extendedEntities = legacy.getAsJsonObject("extended_entities")
            if (extendedEntities != null) {
                val mediaArray = extendedEntities.getAsJsonArray("media")
                if (mediaArray != null) {
                    for (media in mediaArray) {
                        val mediaObj = media.asJsonObject
                        val type = mediaObj.get("type")?.asString

                        if (type == "video" || type == "animated_gif") {
                            // Get thumbnail
                            thumbnailUrl = mediaObj.get("media_url_https")?.asString

                            val videoInfo = mediaObj.getAsJsonObject("video_info")
                            if (videoInfo != null) {
                                val variants = videoInfo.getAsJsonArray("variants")
                                if (variants != null) {
                                    for (variant in variants) {
                                        val vObj = variant.asJsonObject
                                        val vUrl = vObj.get("url")?.asString ?: continue
                                        val bitrate = vObj.get("bitrate")?.asInt ?: 0
                                        val contentType = vObj.get("content_type")?.asString ?: ""

                                        when {
                                            contentType == "video/mp4" -> {
                                                videoVariants.add(VideoVariant(vUrl, bitrate, "video/mp4"))
                                            }
                                            contentType == "application/x-mpegURL" || vUrl.contains(".m3u8") -> {
                                                m3u8Url = vUrl
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Also check legacy.entities as fallback
            if (videoVariants.isEmpty() && m3u8Url == null) {
                val entities = legacy.getAsJsonObject("entities")
                if (entities != null) {
                    val mediaArray = entities.getAsJsonArray("media")
                    if (mediaArray != null) {
                        for (media in mediaArray) {
                            val mediaObj = media.asJsonObject
                            val videoInfo = mediaObj.getAsJsonObject("video_info")
                            if (videoInfo != null) {
                                val variants = videoInfo.getAsJsonArray("variants")
                                if (variants != null) {
                                    for (variant in variants) {
                                        val vObj = variant.asJsonObject
                                        val vUrl = vObj.get("url")?.asString ?: continue
                                        val bitrate = vObj.get("bitrate")?.asInt ?: 0
                                        val contentType = vObj.get("content_type")?.asString ?: ""
                                        if (contentType == "video/mp4") {
                                            videoVariants.add(VideoVariant(vUrl, bitrate, "video/mp4"))
                                        } else if (vUrl.contains(".m3u8")) {
                                            m3u8Url = vUrl
                                        }
                                    }
                                }
                            }
                        }
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
                videoVariants = videoVariants.distinctBy { it.url },
                gifVariants = emptyList(),
                m3u8Url = m3u8Url,
                hasM3u8 = m3u8Url != null
            )
        } catch (e: Exception) {
            return null
        }
    }

    // ==================== Strategy 2: FxTwitter Proxy ====================

    private suspend fun tryFxTwitterApi(tweetId: String, url: String): VideoInfo? {
        return try {
            val apiUrl = "$FXTWITTER_API/$tweetId"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            val tweet = json.getAsJsonObject("tweet") ?: json

            parseThirdPartyResponse(tweet, tweetId, url)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== Strategy 3: VxTwitter Proxy ====================

    private suspend fun tryVxTwitterApi(tweetId: String, url: String): VideoInfo? {
        return try {
            val apiUrl = "$VXTWITTER_API/$tweetId"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject

            parseThirdPartyResponse(json, tweetId, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseThirdPartyResponse(json: com.google.gson.JsonObject, tweetId: String, url: String): VideoInfo? {
        try {
            val id = json.get("tweet_id")?.asString ?: json.get("id")?.asString ?: tweetId
            val text = json.get("text")?.asString ?: ""
            val user = json.getAsJsonObject("user") ?: json.getAsJsonObject("author")
            val authorName = user?.get("name")?.asString ?: "Unknown"
            val authorUsername = user?.get("screen_name")?.asString
                ?: user?.get("username")?.asString ?: "unknown"

            val videoVariants = mutableListOf<VideoVariant>()
            var thumbnailUrl: String? = null
            var m3u8Url: String? = null

            // Parse media array
            val mediaArray = json.getAsJsonArray("media")
            if (mediaArray != null) {
                for (media in mediaArray) {
                    val mediaObj = media.asJsonObject
                    thumbnailUrl = mediaObj.get("thumbnail_url")?.asString
                        ?: mediaObj.get("media_url_https")?.asString ?: thumbnailUrl

                    // Check for direct m3u8
                    val m3u8 = mediaObj.get("m3u8_url")?.asString
                    if (m3u8 != null) m3u8Url = m3u8

                    // Parse video variants
                    val videoInfo = mediaObj.getAsJsonObject("video_info")
                    if (videoInfo != null) {
                        val variants = videoInfo.getAsJsonArray("variants")
                        if (variants != null) {
                            for (variant in variants) {
                                val vObj = variant.asJsonObject
                                val vUrl = vObj.get("url")?.asString ?: continue
                                val bitrate = vObj.get("bitrate")?.asInt ?: 0
                                val contentType = vObj.get("content_type")?.asString ?: ""

                                if (contentType == "video/mp4") {
                                    videoVariants.add(VideoVariant(vUrl, bitrate, "video/mp4"))
                                } else if (vUrl.contains(".m3u8") && m3u8Url == null) {
                                    m3u8Url = vUrl
                                }
                            }
                        }
                    }
                }
            }

            if (videoVariants.isEmpty() && m3u8Url == null) return null

            return VideoInfo(
                tweetId = id,
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

    // ==================== M3U8 Parsing ====================

    suspend fun parseM3u8Playlist(m3u8Url: String): Result<List<M3u8Stream>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(m3u8Url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("获取 M3U8 失败: ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("M3U8 响应为空"))

            val streams = parseM3u8Content(body, m3u8Url)
            if (streams.isEmpty()) {
                return@withContext Result.failure(Exception("M3U8 中未找到视频流"))
            }

            Result.success(streams.sortedByDescending { it.bandwidth })
        } catch (e: Exception) {
            Result.failure(Exception("M3U8 解析错误: ${e.message}"))
        }
    }

    private fun parseM3u8Content(content: String, baseUrl: String): List<M3u8Stream> {
        val streams = mutableListOf<M3u8Stream>()
        val lines = content.lines()
        val basePrefix = baseUrl.substringBeforeLast("/")

        var audioUrl: String? = null

        for (i in lines.indices) {
            val line = lines[i].trim()

            // Extract audio rendition
            if (line.startsWith("#EXT-X-MEDIA") && line.contains("AUDIO")) {
                val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                if (uriMatch != null) {
                    audioUrl = resolveUrl(uriMatch.groupValues[1], basePrefix)
                }
            }

            // Extract video stream
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0

                val resolutionMatch = Regex("RESOLUTION=(\\d+x\\d+)").find(line)
                val resolution = resolutionMatch?.groupValues?.get(1)

                if (i + 1 < lines.size) {
                    val urlLine = lines[i + 1].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                        val videoUrl = resolveUrl(urlLine, basePrefix)
                        val quality = resolutionToQuality(resolution, bandwidth)
                        streams.add(M3u8Stream(
                            videoUrl = videoUrl,
                            audioUrl = audioUrl,
                            bandwidth = bandwidth,
                            resolution = resolution,
                            quality = quality
                        ))
                    }
                }
            }
        }

        // If no streams but content has segments, treat as single stream
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
        return if (url.startsWith("http://") || url.startsWith("https://")) url
        else "$basePrefix/$url"
    }

    private fun resolutionToQuality(resolution: String?, bandwidth: Long): String {
        if (resolution != null) {
            val height = resolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0
            return when {
                height >= 2160 -> "4K"
                height >= 1440 -> "2K"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
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
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }
}
