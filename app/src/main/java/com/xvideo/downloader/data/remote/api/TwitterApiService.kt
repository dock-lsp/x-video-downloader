package com.xvideo.downloader.data.remote.api

import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.data.model.VideoVariant
import com.xvideo.downloader.data.model.GifVariant
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TwitterApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Alternative API endpoints for parsing Twitter videos
    private val apiEndpoints = listOf(
        "https://api.vxtwitter.com/{tweetId}",
        "https://twitsave.com/info?url={url}",
        "https://tweeterid.com/ajax.php?input={username}"
    )

    suspend fun parseTweetUrl(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            // Validate URL
            val tweetId = extractTweetId(url)
                ?: return@withContext Result.failure(Exception("Invalid Twitter/X URL"))

            // Try to fetch video info using alternative methods
            val videoInfo = tryFetchFromApi(tweetId, url)
                ?: tryFetchFromMobileApi(tweetId, url)
                ?: tryFetchFromWebpage(tweetId, url)
                ?: return@withContext Result.failure(Exception("Could not parse video from tweet"))

            Result.success(videoInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTweetId(url: String): String? {
        // Handle various Twitter URL formats
        val patterns = listOf(
            Pattern.compile("twitter\\.com/\\w+/status/(\\d+)"),
            Pattern.compile("x\\.com/\\w+/status/(\\d+)"),
            Pattern.compile("(\\d{10,20})") // Fallback to numeric ID
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private suspend fun tryFetchFromApi(tweetId: String, url: String): VideoInfo? {
        try {
            // Try vxtwitter API (returns tweet data including video URLs)
            val apiUrl = "https://api.vxtwitter.com/$tweetId"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                return parseVxTwitterResponse(body, url)
            }
        } catch (e: Exception) {
            // Try next method
        }
        return null
    }

    private fun parseVxTwitterResponse(json: String, url: String): VideoInfo? {
        try {
            val parser = JsonParser.parseString(json).asJsonObject
            val tweetId = parser.get("tweet_id")?.asString ?: return null
            val text = parser.get("text")?.asString ?: ""
            val user = parser.get("user")?.asJsonObject
            val authorName = user?.get("name")?.asString ?: "Unknown"
            val authorUsername = user?.get("screen_name")?.asString ?: "unknown"

            val videoVariants = mutableListOf<VideoVariant>()
            val gifVariants = mutableListOf<GifVariant>()
            var thumbnailUrl: String? = null

            // Parse media
            val mediaArray = parser.getAsJsonArray("media")
            if (mediaArray != null) {
                for (media in mediaArray) {
                    val mediaObj = media.asJsonObject
                    if (mediaObj.get("type")?.asString == "video") {
                        thumbnailUrl = mediaObj.get("media_url_https")?.asString

                        val videoInfo = mediaObj.getAsJsonObject("video_info")
                        if (videoInfo != null) {
                            val variants = videoInfo.getAsJsonArray("variants")
                            for (variant in variants) {
                                val variantObj = variant.asJsonObject
                                val url = variantObj.get("url")?.asString ?: continue
                                val bitrate = variantObj.get("bitrate")?.asInt ?: 0
                                val contentType = variantObj.get("content_type")?.asString ?: "video/mp4"

                                if (contentType == "video/mp4") {
                                    videoVariants.add(VideoVariant(url, bitrate, contentType))
                                }
                            }
                        }
                    }
                }
            }

            if (videoVariants.isEmpty()) return null

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = authorName,
                authorUsername = authorUsername,
                tweetText = text,
                thumbnailUrl = thumbnailUrl,
                videoVariants = videoVariants,
                gifVariants = gifVariants
            )
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun tryFetchFromMobileApi(tweetId: String, url: String): VideoInfo? {
        try {
            // Try mobile.twitter.com
            val request = Request.Builder()
                .url("https://mobile.twitter.com/i/status/$tweetId")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                return parseMobileResponse(body, url)
            }
        } catch (e: Exception) {
            // Try next method
        }
        return null
    }

    private fun parseMobileResponse(html: String, url: String): VideoInfo? {
        try {
            // Extract JSON data from HTML
            val jsonPattern = Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)
            val jsonMatcher = jsonPattern.matcher(html)

            if (jsonMatcher.find()) {
                val json = jsonMatcher.group(1)
                return parseVxTwitterResponse(json, url)
            }

            // Fallback: try to find video URLs directly in HTML
            val videoVariants = mutableListOf<VideoVariant>()
            val videoPattern = Pattern.compile("https://video\\.twimg\\.com/[^\\s\"']+\\.mp4[^\s\"']*")
            val videoMatcher = videoPattern.matcher(html)

            while (videoMatcher.find()) {
                val videoUrl = videoMatcher.group()
                val bitrate = extractBitrateFromUrl(videoUrl)
                videoVariants.add(VideoVariant(videoUrl, bitrate, "video/mp4"))
            }

            if (videoVariants.isEmpty()) return null

            val authorPattern = Pattern.compile("data-screen-name=\"([^\"]+)\"")
            val authorMatcher = authorPattern.matcher(html)
            val authorUsername = if (authorMatcher.find()) authorMatcher.group(1) else "unknown"

            val textPattern = Pattern.compile("data-text=\"([^\"]+)\"")
            val textMatcher = textPattern.matcher(html)
            val tweetText = if (textMatcher.find()) textMatcher.group(1) ?: "" else ""

            val tweetId = extractTweetId(url) ?: "unknown"

            return VideoInfo(
                tweetId = tweetId,
                tweetUrl = url,
                authorName = authorUsername,
                authorUsername = authorUsername,
                tweetText = tweetText,
                thumbnailUrl = null,
                videoVariants = videoVariants,
                gifVariants = emptyList()
            )
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun tryFetchFromWebpage(tweetId: String, url: String): VideoInfo? {
        try {
            // Try x.com
            val request = Request.Builder()
                .url("https://x.com/i/status/$tweetId")
                .addHeader("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                return parseMobileResponse(body, url)
            }
        } catch (e: Exception) {
            // Return failure
        }
        return null
    }

    private fun extractBitrateFromUrl(url: String): Int {
        // Try to extract bitrate from URL parameters
        val bitratePattern = Pattern.compile("bitrate=(\\d+)")
        val matcher = bitratePattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull() ?: 0
        } else {
            0
        }
    }

    companion object {
        @Volatile
        private var instance: TwitterApiService? = null

        fun getInstance(): TwitterApiService {
            return instance ?: synchronized(this) {
                instance ?: TwitterApiService().also { instance = it }
            }
        }
    }
}
