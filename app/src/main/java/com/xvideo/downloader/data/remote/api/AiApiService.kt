package com.xvideo.downloader.data.remote.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xvideo.downloader.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI Service supporting OpenAI-compatible APIs.
 * Works with OpenAI, DeepSeek, Moonshot, Qwen, and other compatible providers.
 */
class AiApiService {

    // Use a dedicated OkHttpClient with longer timeouts for AI requests
    private val okHttpClient: OkHttpClient by lazy {
        try {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create OkHttpClient, using default", e)
            OkHttpClient()
        }
    }

    private val gson = Gson()

    companion object {
        private const val TAG = "AiApiService"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val MAX_CONTEXT_MESSAGES = 20

        @Volatile
        private var instance: AiApiService? = null

        fun getInstance(): AiApiService {
            return instance ?: synchronized(this) {
                instance ?: AiApiService().also { instance = it }
            }
        }
    }

    /**
     * Get API config from SharedPreferences.
     */
    private fun getConfig(): AiConfig {
        val prefs = App.getInstance().getSharedPreferences("ai_settings", android.content.Context.MODE_PRIVATE)
        return AiConfig(
            baseUrl = prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            apiKey = prefs.getString("api_key", "") ?: "",
            model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
            systemPrompt = prefs.getString("system_prompt", getDefaultSystemPrompt()) ?: getDefaultSystemPrompt()
        )
    }

    fun isConfigured(): Boolean {
        val config = getConfig()
        return config.apiKey.isNotBlank()
    }

    /**
     * Send a chat message and get a response.
     * @param messages List of message pairs (role, content)
     * @return AI response text
     */
    suspend fun chat(messages: List<Pair<String, String>>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig()
            if (config.apiKey.isBlank()) {
                return@withContext Result.failure(Exception("请先在设置中配置 AI API Key"))
            }

            val url = "${config.baseUrl.trimEnd('/')}/chat/completions"

            // Build messages array
            val messagesArray = com.google.gson.JsonArray()

            // System prompt
            val systemMsg = JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", config.systemPrompt)
            }
            messagesArray.add(systemMsg)

            // Conversation context (limited to MAX_CONTEXT_MESSAGES)
            val contextMessages = messages.takeLast(MAX_CONTEXT_MESSAGES)
            for ((role, content) in contextMessages) {
                val msg = JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                }
                messagesArray.add(msg)
            }

            // Request body
            val body = JsonObject().apply {
                addProperty("model", config.model)
                add("messages", messagesArray)
                addProperty("temperature", 0.7)
                addProperty("max_tokens", 4096)
            }

            val requestBody = gson.toJson(body)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending AI request to: $url (model: ${config.model})")

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "AI API error: ${response.code} - $responseBody")
                val errorMsg = try {
                    val json = JsonParser.parseString(responseBody).asJsonObject
                    json.getAsJsonObject("error")?.get("message")?.asString
                        ?: "API 请求失败 (${response.code})"
                } catch (_: Exception) {
                    "API 请求失败 (${response.code})"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return@withContext Result.failure(Exception("AI 返回为空"))
            }

            val message = choices[0].asJsonObject.getAsJsonObject("message")
            val content = message.get("content")?.asString ?: ""

            Log.d(TAG, "AI response received, length: ${content.length}")
            Result.success(content)

        } catch (e: Exception) {
            Log.e(TAG, "AI chat failed", e)
            Result.failure(Exception("AI 请求失败: ${e.message}"))
        }
    }

    /**
     * Generate code with context awareness.
     */
    suspend fun generateCode(
        prompt: String,
        conversationHistory: List<Pair<String, String>>
    ): Result<String> {
        val messages = conversationHistory.toMutableList()
        messages.add("user" to prompt)
        return chat(messages)
    }

    private fun getDefaultSystemPrompt(): String {
        return """你是一个专业的 AI 编程助手。你的任务是帮助用户编写、修改和优化代码。

当用户请求你生成代码时，请遵循以下规则：
1. 每个代码块必须包含文件路径注释，格式：// 文件路径: path/to/file.ext
2. 按照功能模块组织目录结构
3. 代码要完整、可运行，不要省略关键部分
4. 使用清晰的注释说明代码功能
5. 如果是修改现有代码，请明确指出修改的部分

示例输出格式：
```kotlin
// 文件路径: app/src/main/java/com/example/MyClass.kt
package com.example

class MyClass {
    // 实现代码...
}
```

```xml
// 文件路径: app/src/main/res/layout/activity_main.xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout ...>
</LinearLayout>
```

请用中文回复。代码块中的文件路径会被自动识别并创建对应文件。"""
    }

    data class AiConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val systemPrompt: String
    )
}
