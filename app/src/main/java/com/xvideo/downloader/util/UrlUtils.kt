package com.xvideo.downloader.util

object UrlUtils {

    private val twitterPatterns = listOf(
        Regex("twitter\\.com/\\w+/status/\\d+"),
        Regex("twitter\\.com/\\w+/status/\\w+"),
        Regex("x\\.com/\\w+/status/\\d+"),
        Regex("x\\.com/\\w+/status/\\w+"),
        Regex("https://t\\.co/\\w+") // Twitter short URLs
    )

    fun isValidTwitterUrl(url: String): Boolean {
        val trimmedUrl = url.trim()
        return twitterPatterns.any { it.containsMatchIn(trimmedUrl) }
    }

    fun normalizeTwitterUrl(url: String): String {
        var normalized = url.trim()
        
        // Convert mobile.twitter.com to x.com
        normalized = normalized.replace("mobile.twitter.com", "x.com")
        
        // Convert twitter.com to x.com
        normalized = normalized.replace("twitter.com", "x.com")
        
        // Convert t.co short URLs - these need to be resolved separately
        if (normalized.contains("t.co")) {
            // Return as-is, will be resolved during parsing
            return normalized
        }
        
        return normalized
    }

    fun extractTweetId(url: String): String? {
        val patterns = listOf(
            Regex("twitter\\.com/\\w+/status/(\\d+)"),
            Regex("x\\.com/\\w+/status/(\\d+)"),
            Regex("status/(\\d+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun extractUsername(url: String): String? {
        val patterns = listOf(
            Regex("twitter\\.com/(\\w+)/status"),
            Regex("x\\.com/(\\w+)/status")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun formatUrl(url: String): String {
        return normalizeTwitterUrl(url)
    }
}
